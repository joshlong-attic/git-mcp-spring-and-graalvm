package com.github.asm0dey.git_mcp_spring;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.github.asm0dey.git_mcp_spring.Result.failure;
import static com.github.asm0dey.git_mcp_spring.Result.success;
import static org.eclipse.jgit.api.ResetCommand.ResetType.HARD;

@SpringBootApplication
public class GitMcpSpringApplication {

	public static void main(String[] args) {
		SpringApplication.run(GitMcpSpringApplication.class, args);
	}

	@Bean
	ToolCallbackProvider gitTools(GitRepositoryService repo, GitLogService log, GitReflogService reflog,
			GitInteractiveRebaseService rebase) {
		return MethodToolCallbackProvider.builder().toolObjects(repo, reflog, log, rebase).build();
	}

	@Bean
	static GitMcpBeanFactoryInitializationAotProcessor gitMcpBeanFactoryInitializationAotProcessor() {
		return new GitMcpBeanFactoryInitializationAotProcessor();
	}

	static class GitMcpBeanFactoryInitializationAotProcessor implements BeanFactoryInitializationAotProcessor {

		private final Logger log = LoggerFactory.getLogger(this.getClass());

		@Override
		public BeanFactoryInitializationAotContribution processAheadOfTime(
				ConfigurableListableBeanFactory beanFactory) {
			var typesForReflection = new HashSet<Class<?>>();
			var rootPackages = new HashSet<>(AutoConfigurationPackages.get(beanFactory));
			rootPackages.add("org.eclipse.jgit");

			for (var packageName : rootPackages) {
				for (var beanDefinition : this.find(packageName)) {
					var clzz = this.classFor(beanDefinition);
					if (clzz != null) {
						if (clzz.isEnum() || clzz.isRecord())
							typesForReflection.add(clzz);
					}
				}
			}

			return (ctx, code) -> {
				var reflection = ctx.getRuntimeHints().reflection();
				for (var clazz : typesForReflection) {
					log.info("registering {} for reflection.", clazz.getName());
					reflection.registerType(clazz, MemberCategory.values());
				}
			};
		}

		private Set<BeanDefinition> find(String pgk) {
			var cpp = new ClassPathScanningCandidateComponentProvider(false) {
				@Override
				protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
					return true;
				}

				@Override
				protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
					return true;
				}
			};
			return cpp.findCandidateComponents(pgk);
		}

		private Class<?> classFor(BeanDefinition beanDefinition) {
			try {
				return Class.forName(beanDefinition.getBeanClassName());
			}
			catch (Throwable e) {
				// don't care..
			}
			return null;
		}

	}

}

sealed interface Result<T> permits Result.Success, Result.Failure {

	public record Success<T>(T value) implements Result<T> {
	}

	public record Failure<T>(String message) implements Result<T> {
	}

	static <T> Result<T> success(T value) {
		return new Success<>(value);
	}

	static <T> Result<T> failure(String message) {
		return new Failure<>(message);
	}

}

/**
 * Service for Git reflog operations. Provides methods for reflog access and management.
 */
@Service
class GitReflogService {

	private final Logger logger = LoggerFactory.getLogger(GitReflogService.class);

	private final GitRepositoryService repository;

	public record ReflogCommitter(String name, String email, LocalDateTime time) {
	}

	/**
	 * Represents a reflog entry with commit information and index.
	 *
	 * @param oldId Previous commit ID
	 * @param newId New commit ID
	 * @param message Reflog message
	 * @param committer Committer information
	 * @param refName Name of the ref
	 * @param index Index in reflog (used for HEAD@{n} format)
	 */
	public record ReflogEntry(String oldId, String newId, String message, ReflogCommitter committer, String refName,
			int index) {
	}

	/**
	 * Creates a new Git reflog service.
	 * @param repository Git repository resource
	 */
	public GitReflogService(GitRepositoryService repository) {
		this.repository = repository;
	}

	/**
	 * Gets the reflog for a specific ref.
	 * @param refName Name of the ref (e.g., "HEAD", "refs/heads/main")
	 * @param maxCount Maximum number of entries to return (0 for all)
	 * @return List of reflog entries
	 */
	@Tool(name = "git_reflog_get",
			description = "Gets the reflog (reference log) for a specific ref - shows history of ref "
					+ "updates/movements, not commit history. Use this to see when HEAD or branches were moved, reset,"
					+ " or updated. Different from git_log which shows commit history.")
	public Result<List<ReflogEntry>> getReflog(String refName, int maxCount) {
		if (refName == null || refName.trim().isEmpty()) {
			refName = "HEAD";
		}
		if (maxCount < 0) {
			maxCount = 0;
		}
		var ref = refName;
		if (!repository.isHasRepo())
			return new Result.Failure<>("Repository is not open");
		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();
			var reader = repo.getRefDatabase().getReflogReader(ref);
			if (reader == null) {
				logger.warn("No reflog found for ref: {}", ref);
				return failure("No reflog found for ref: " + ref);
			}
			var entries = reader.getReverseEntries();
			return success(entries.stream()
				.limit(maxCount > 0 ? maxCount : Long.MAX_VALUE)//
				.map(entry -> new ReflogEntry(entry.getOldId().name(), entry.getNewId().name(), entry.getComment(),
						new ReflogCommitter(entry.getWho().getName(), entry.getWho().getEmailAddress(),
								LocalDateTime.ofInstant(entry.getWho().getWhenAsInstant(), ZoneId.systemDefault())),
						ref, entries.indexOf(entry)))//
				.collect(Collectors.toList()));
		}
		catch (Exception e) {
			logger.error("Error reading reflog for ref: {}", ref, e);
			return failure("Error reading reflog for ref: " + ref);
		}

	}

	/**
	 * Reverts the repository state to a specific reflog entry.
	 * @param refName Name of the ref (e.g., "HEAD", "refs/heads/main")
	 * @param commitId The commit ID to revert to
	 * @return true if revert was successful, false otherwise
	 */
	@Tool(name = "git_reflog_revert", description = "Reverts the repository state to a specific reflog entry using"
			+ " commit ID. This performs a hard reset to restore the repository to a previous state recorded in the reflog. "
			+ "Use with caution as it changes working directory.")
	public boolean revertReflog(String refName, String commitId) {
		if (refName == null || refName.trim().isEmpty()) {
			refName = "HEAD";
		}
		try (var git = new Git(repository.getRepository())) {
			git.reset().setMode(HARD).setRef(commitId).call();
			return true;
		}
		catch (Exception e) {
			logger.error("Error reverting to reflog entry: {} at {}", refName, commitId, e);
			return false;
		}
	}

	private Result<String[]> parseReflogExpression(String expression) {
		if (expression == null || expression.trim().isEmpty()) {
			return null;
		}
		var pattern = "(.+)@\\{(\\d+)}";
		var matcher = Pattern.compile(pattern).matcher(expression);
		if (matcher.matches()) {
			var refName = matcher.group(1);
			var index = Integer.parseInt(matcher.group(2));
			var res = getReflog(refName, index + 1);
			switch (res) {
				case Result.Failure(String m) -> {
					return failure(m);
				}
				case Result.Success(List<ReflogEntry> entries) -> {
					if (entries.size() > index) {
						return success(new String[] { refName, entries.get(index).newId() });
					}
				}
			}
		}
		return null;
	}

	/**
	 * Reverts the repository state to a specific reflog entry using ref@{n} syntax.
	 * @param expression The reflog expression (e.g., "HEAD@{3}", "refs/heads/main@{2}")
	 * @return true if revert was successful, false otherwise
	 */
	@Tool(name = "git_reflog_revert_expression",
			description = "Reverts the repository state to a specific reflog entry using ref@{n} syntax (e.g., HEAD@{3}). "
					+ "This performs a hard reset to restore the repository to a previous state from the reflog history. "
					+ "Use with caution as it changes working directory.")
	public boolean revertReflog(String expression) {
		var refInfo = parseReflogExpression(expression);
		switch (refInfo) {
			case Result.Failure(String m) -> {
				logger.error("Error parsing reflog expression {}: {}", expression, m);
				return false;
			}
			case Result.Success(String[] ref) -> {
				return revertReflog(ref[0], ref[1]);
			}
		}
	}

}

/**
 * Represents a Git repository as a resource in the MCP protocol. Provides methods to
 * interact with the repository.
 */
@Service
class GitRepositoryService {

	private static final Logger logger = LoggerFactory.getLogger(GitRepositoryService.class);

	private Repository repository;

	private Git git;

	private GitRepositoryHolder config;

	private boolean hasRepo = false;

	boolean isHasRepo() {
		return hasRepo;
	}

	/**
	 * Opens the repository.
	 * @return True if the repository was opened successfully, false otherwise
	 */
	@Tool(name = "git_open", description = "Opens the repository")
	public boolean open(String path) {
		close();

		try {
			var gitDir = new File(path, ".git");
			if (!gitDir.exists())
				gitDir = new File(path); // Bare repository

			var builder = new FileRepositoryBuilder();
			repository = builder.setGitDir(gitDir)
				.readEnvironment() // Scan environment GIT_* variables
				.findGitDir() // Scan up the file system tree
				.build();

			git = new Git(repository);
			config = new GitRepositoryHolder(repository);
			logger.info("Opened Git repository: {}", path);
			hasRepo = true;
			return true;
		}
		catch (IOException e) {
			logger.error("Failed to open Git repository: {}", path, e);
			hasRepo = false;
			return false;
		}
	}

	@Tool(name = "git_close", description = "Closes the repository")
	public void close() {
		if (git != null) {
			git.close();
			var path = git.getRepository().getDirectory().toPath();
			logger.debug("Closed Git repository: {}", path);
		}
		if (repository != null)
			repository.close();

		hasRepo = false;
	}

	/**
	 * Gets the repository path.
	 * @return Repository path
	 */
	public Path getPath() {
		return git.getRepository().getDirectory().toPath();
	}

	/**
	 * Gets the JGit repository object.
	 * @return JGit repository
	 */
	public Repository getRepository() {
		return repository;
	}

	/**
	 * Gets the JGit Git object.
	 * @return JGit Git
	 */
	public Git getGit() {
		return git;
	}

	/**
	 * Gets the repository configuration.
	 * @return Repository configuration
	 */
	public GitRepositoryHolder getConfig() {
		return config;
	}

	/**
	 * Gets a configuration value.
	 * @param section Configuration section
	 * @param name Configuration name
	 * @return Configuration value or null if not found
	 */
	public String getConfigValue(String section, String name) {
		if (config == null) {
			return null;
		}
		return config.getString(section, name);
	}

	/**
	 * Gets a configuration value.
	 * @param section Configuration section
	 * @param subsection Configuration subsection
	 * @param name Configuration name
	 * @return Configuration value or null if not found
	 */
	public String getConfigValue(String section, String subsection, String name) {
		if (config == null) {
			return null;
		}
		return config.getString(section, subsection, name);
	}

	/**
	 * Sets a configuration value.
	 * @param section Configuration section
	 * @param name Configuration name
	 * @param value Configuration value
	 * @return True if the value was set and saved successfully, false otherwise
	 */
	public boolean setConfigValue(String section, String name, String value) {
		if (config == null) {
			return false;
		}
		config.setString(section, name, value);
		return config.save();
	}

	/**
	 * Sets a configuration value.
	 * @param section Configuration section
	 * @param subsection Configuration subsection
	 * @param name Configuration name
	 * @param value Configuration value
	 * @return True if the value was set and saved successfully, false otherwise
	 */
	public boolean setConfigValue(String section, String subsection, String name, String value) {
		if (config == null) {
			return false;
		}
		config.setString(section, subsection, name, value);
		return config.save();
	}

}

@Service
class GitLogService {

	private final GitRepositoryService repository;

	GitLogService(GitRepositoryService repository) {
		this.repository = repository;
	}

	public record CommitInfo(String commitId, String shortMessage, String fullMessage, PersonInfo author,
			PersonInfo committer, Instant commitTime, List<String> tags, List<String> branches,
			List<FileChangeInfo> fileChanges, List<DiffInfo> diffs) {
	}

	public record FileChangeInfo(String path, String changeType) {
	}

	public record DiffInfo(String oldPath, String newPath, String diff) {
	}

	public record PersonInfo(String name, String email, Instant when) {
		static PersonInfo from(PersonIdent ident) {
			return new PersonInfo(ident.getName(), ident.getEmailAddress(), ident.getWhenAsInstant());
		}
	}

	public record GitLogOptions(boolean showAuthors, boolean showCommitters, boolean fullMessages,
			boolean showCommitIds, boolean showDates, boolean showTags, boolean showBranches, boolean showDiffs,
			boolean showFileChanges, int maxCount) {
		public static GitLogOptions defaults() {
			return new GitLogOptions(true, false, false, true, true, false, false, false, false, 30);
		}
	}

	@Tool(name = "git_log",
			description = "Gets the commit history log - shows chronological list of commits with their messages, authors, and changes. Different from git_reflog which shows reference movement history.")
	List<CommitInfo> getLog(GitLogOptions options) throws GitAPIException {
		try (var git = new Git(repository.getRepository())) {
			var logCommand = git.log();

			if (options.maxCount() > 0) {
				logCommand.setMaxCount(options.maxCount());
			}

			var logs = (Iterable<RevCommit>) null;
			try {
				logs = logCommand.call();
			}
			catch (NoHeadException e) {
				return List.of();
			}

			return StreamSupport //
				.stream(logs.spliterator(), false) //
				.map(commit -> {
					try {
						return toCommitInfo(commit, options, git);
					}
					catch (GitAPIException e) {
						throw new RuntimeException(e);
					}
				}) //
				.collect(Collectors.toList());
		}
	}

	private CommitInfo toCommitInfo(RevCommit commit, GitLogOptions options, Git git) throws GitAPIException {
		try {
			var diffs = Collections.<DiffEntry>emptyList();
			if (commit.getParentCount() > 0) {
				try (var formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
					formatter.setRepository(git.getRepository());
					diffs = formatter.scan(commit.getParent(0).getTree(), commit.getTree());
				}
			}

			return new CommitInfo(commit.getName(), commit.getShortMessage(),
					options.fullMessages() ? commit.getFullMessage() : commit.getShortMessage(),
					PersonInfo.from(commit.getAuthorIdent()), PersonInfo.from(commit.getCommitterIdent()),
					Instant.ofEpochSecond(commit.getCommitTime()),
					options.showTags() ? getTagsForCommit(commit, git) : List.of(),
					options.showBranches() ? getBranchesForCommit(commit, git) : List.of(),
					options.showFileChanges() ? diffs.stream()
						.map(d -> new FileChangeInfo(d.getNewPath(), d.getChangeType().name()))
						.collect(Collectors.toList()) : List.of(),
					options.showDiffs() ? diffs.stream()
						.map(d -> new DiffInfo(d.getOldPath(), d.getNewPath(), getDiffText(d, git.getRepository())))
						.collect(Collectors.toList()) : List.of());
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private String getDiffText(DiffEntry diff, Repository repository) {
		var out = new ByteArrayOutputStream();
		try (var formatter = new DiffFormatter(out)) {
			formatter.setRepository(repository);
			formatter.format(diff);
			return out.toString(StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			return "Error getting diff: " + e.getMessage();
		}
	}

	private List<String> getTagsForCommit(RevCommit commit, Git git) throws GitAPIException {
		return git.tagList() //
			.call() //
			.stream() //
			.filter(ref -> {
				try {
					var repo = repository.getRepository();
					return repo.getRefDatabase().peel(ref).getPeeledObjectId().equals(commit.getId());
				}
				catch (Exception e) {
					return false;
				}
			}) //
			.map(ref -> ref.getName().replaceFirst("^refs/tags/", "")) //
			.collect(Collectors.toList());
	}

	private List<String> getBranchesForCommit(RevCommit commit, Git git) throws GitAPIException {
		return git.branchList() //
			.call() //
			.stream() //
			.filter(ref -> {
				try {
					return repository.getRepository().getRefDatabase().peel(ref).getObjectId().equals(commit.getId());
				}
				catch (Exception e) {
					return false;
				}
			}) //
			.map(ref -> ref.getName().replaceFirst("^refs/heads/", "")) //
			.collect(Collectors.toList());
	}

	@Tool(name = "git_commit_info",
			description = "Gets detailed information about a specific commit "
					+ "by commit ID or reference - shows commit message, author, changes, and metadata. "
					+ "This is for examining individual commits, not reference history.")
	public CommitInfo getCommitInfo(String commitIdOrRef, GitLogOptions options) throws GitAPIException {
		try (var git = new Git(repository.getRepository())) {
			var commit = (RevCommit) null;
			var repo = git.getRepository();
			try {
				commit = git.getRepository().parseCommit(repo.resolve(commitIdOrRef));
			}
			catch (Exception e) {
				//
			}
			if (commit == null)
				throw new IllegalArgumentException("Commit or reference not found: " + commitIdOrRef);
			return toCommitInfo(commit, options, git);
		}
	}

}

/**
 * Service for Git interactive rebase operations. Provides methods for interactive rebase
 * functionality including commit manipulation.
 */
@Service
class GitInteractiveRebaseService {

	private final Logger logger = LoggerFactory.getLogger(GitInteractiveRebaseService.class);

	private final GitRepositoryService repository;

	/**
	 * Represents a commit in the rebase plan.
	 */
	public record RebaseCommit(String commitId, String message, String author, String authorEmail, LocalDateTime date,
			RebaseAction action) {
	}

	/**
	 * Represents display settings for commit information.
	 */
	public record CommitDisplaySettings(boolean useFullCommitId, boolean useFullMessage) {
		public static CommitDisplaySettings shortFormat() {
			return new CommitDisplaySettings(false, false);
		}

		public static CommitDisplaySettings fullFormat() {
			return new CommitDisplaySettings(true, true);
		}

		public static CommitDisplaySettings custom(boolean useFullCommitId, boolean useFullMessage) {
			return new CommitDisplaySettings(useFullCommitId, useFullMessage);
		}
	}

	/**
	 * Represents a commit with a numeric ID for easy reference.
	 */
	public record NumberedCommit(int id, String commitId, String message, String author) {
	}

	/**
	 * Represents the action to perform during an interactive rebase.
	 */
	public enum RebaseAction {

		PICK("pick", "Keep the commit as is"),
		SQUASH("squash", "Combine with the previous commit, keeping both messages"),
		DROP("drop", "Remove the commit entirely"), REWORD("reword", "Keep the commit but edit the message"),
		EDIT("edit", "Stop for manual editing of the commit"),
		FIXUP("fixup", "Combine with the previous commit, discarding this commit's message");

		private final String command;

		private final String description;

		RebaseAction(String command, String description) {
			this.command = command;
			this.description = description;
		}

		public String getCommand() {
			return command;
		}

		public String getDescription() {
			return description;
		}

		/**
		 * Parse a string command to RebaseAction enum.
		 */
		public static RebaseAction fromCommand(String command) {
			if (command == null) {
				return null;
			}
			var lowerCommand = command.toLowerCase().trim();
			for (RebaseAction action : values()) {
				if (action.command.equals(lowerCommand)) {
					return action;
				}
			}
			return null;
		}

		/**
		 * Convert to JGit's RebaseTodoLine.Action.
		 */
		public RebaseTodoLine.Action toJGitAction() {
			return switch (this) {
				case PICK -> org.eclipse.jgit.lib.RebaseTodoLine.Action.PICK;
				case SQUASH -> org.eclipse.jgit.lib.RebaseTodoLine.Action.SQUASH;
				case DROP -> org.eclipse.jgit.lib.RebaseTodoLine.Action.COMMENT; // JGit
				// doesn't
				// have
				// DROP,
				// use
				// COMMENT
				// to
				// skip
				case REWORD -> org.eclipse.jgit.lib.RebaseTodoLine.Action.REWORD;
				case EDIT -> org.eclipse.jgit.lib.RebaseTodoLine.Action.EDIT;
				case FIXUP -> org.eclipse.jgit.lib.RebaseTodoLine.Action.FIXUP;
			};
		}

	}

	/**
	 * Represents a rebase instruction.
	 */
	public record RebaseInstruction(RebaseAction action, int commitId, // numeric ID of
			// the commit
			String newMessage // for reword action
	) {
	}

	/**
	 * Represents the result of a rebase operation.
	 */
	public record RebaseExecutionResult(boolean success, boolean hasConflicts, String message,
			List<String> conflictedFiles) {
	}

	/**
	 * Represents the current state of an interactive rebase.
	 */
	public record RebaseStatus(boolean isInProgress, int currentStep, int totalSteps, List<RebaseCommit> commits,
			boolean conflicted) {
	}

	/**
	 * Options for starting an interactive rebase.
	 */
	public record RebaseOptions(String baseCommit, boolean preserve) {
		public static RebaseOptions defaults(String baseCommit) {
			return new RebaseOptions(baseCommit, false);
		}
	}

	/**
	 * Creates a new Git interactive rebase service.
	 */
	public GitInteractiveRebaseService(GitRepositoryService repository) {
		this.repository = repository;
	}

	/**
	 * Gets the current status of an interactive rebase.
	 */
	@Tool(name = "git_rebase_status",
			description = "Gets the current status of an interactive rebase, including whether one is in progress, current step, and list of commits involved.")
	public Result<RebaseStatus> getRebaseStatus() {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();

			var inProgress = repo.getRepositoryState().isRebasing();

			if (!inProgress) {
				return success(new RebaseStatus(false, 0, 0, List.of(), false));
			}

			var commits = new ArrayList<RebaseCommit>();
			var conflicted = repo.getRepositoryState().equals(org.eclipse.jgit.lib.RepositoryState.REBASING_MERGE);

			return success(new RebaseStatus(true, 0, 0, commits, conflicted));

		}
		catch (Exception e) {
			logger.error("Error getting rebase status", e);
			return failure("Error getting rebase status: " + e.getMessage());
		}
	}

	/**
	 * Starts an interactive rebase.
	 */
	@Tool(name = "git_rebase_start",
			description = "Starts an interactive rebase from the specified base commit. Use commit hashes, branch names, or relative references like HEAD~3. This allows you to modify commit history.")
	public Result<String> startInteractiveRebase(RebaseOptions options) {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		if (options.baseCommit == null || options.baseCommit.trim().isEmpty()) {
			return failure("Base commit is required");
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();
			if (repo.getRepositoryState().isRebasing()) {
				return failure(
						"A rebase is already in progress. Use git_rebase_continue, git_rebase_abort, or git_rebase_skip.");
			}

			var baseCommitId = (ObjectId) null;
			try {
				baseCommitId = repo.resolve(options.baseCommit);
				if (baseCommitId == null) {
					return failure("Could not resolve base commit: " + options.baseCommit);
				}
			}
			catch (IOException e) {
				return failure("Invalid base commit: " + options.baseCommit);
			}

			var rebaseCommand = git.rebase()
				.setUpstream(baseCommitId)
				.runInteractively(new org.eclipse.jgit.api.RebaseCommand.InteractiveHandler() {
					@Override
					public void prepareSteps(List<org.eclipse.jgit.lib.RebaseTodoLine> steps) {
						// Default behavior - keep all steps as "pick"
					}

					@Override
					public String modifyCommitMessage(String commit) {
						return commit;
					}
				});

			if (options.preserve) {
				rebaseCommand.setPreserveMerges(true);
			}

			var result = rebaseCommand.call();

			return switch (result.getStatus()) {
				case OK -> success("Interactive rebase completed successfully");
				case STOPPED -> success("Rebase stopped for editing. Use git_rebase_continue when ready.");
				case CONFLICTS ->
					success("Rebase stopped due to conflicts. Resolve conflicts and use git_rebase_continue.");
				case UNCOMMITTED_CHANGES -> failure("Cannot start rebase with uncommitted changes");
				case FAILED -> failure("Rebase failed: " + (result.getFailingPaths() != null
						? String.join(", ", result.getFailingPaths().keySet()) : "Unknown error"));
				default -> failure("Rebase ended with status: " + result.getStatus());
			};

		}
		catch (GitAPIException e) {
			logger.error("Error starting interactive rebase", e);
			return failure("Error starting interactive rebase: " + e.getMessage());
		}
	}

	/**
	 * Continues an interactive rebase after resolving conflicts or making edits.
	 */
	@Tool(name = "git_rebase_continue",
			description = "Continues an interactive rebase after resolving conflicts or completing edits. Use this after staging resolved conflicts or making required changes.")
	public Result<String> continueRebase() {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();

			if (!repo.getRepositoryState().isRebasing()) {
				return failure("No rebase in progress");
			}

			var result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call();

			return switch (result.getStatus()) {
				case OK -> success("Rebase completed successfully");
				case STOPPED -> success("Rebase stopped for editing. Use git_rebase_continue when ready.");
				case CONFLICTS ->
					success("Rebase stopped due to conflicts. Resolve conflicts and use git_rebase_continue.");
				case FAILED -> failure("Rebase failed: " + (result.getFailingPaths() != null
						? String.join(", ", result.getFailingPaths().keySet()) : "Unknown error"));
				default -> failure("Rebase ended with status: " + result.getStatus());
			};

		}
		catch (GitAPIException e) {
			logger.error("Error continuing rebase", e);
			return failure("Error continuing rebase: " + e.getMessage());
		}
	}

	/**
	 * Skips the current commit in an interactive rebase.
	 */
	@Tool(name = "git_rebase_skip",
			description = "Skips the current commit in an interactive rebase. Use this when you want to exclude the current commit from the rebase.")
	public Result<String> skipRebase() {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();

			if (!repo.getRepositoryState().isRebasing()) {
				return failure("No rebase in progress");
			}

			var result = git.rebase().setOperation(RebaseCommand.Operation.SKIP).call();

			return switch (result.getStatus()) {
				case OK -> success("Rebase completed successfully");
				case STOPPED -> success("Rebase stopped for editing. Use git_rebase_continue when ready.");
				case CONFLICTS ->
					success("Rebase stopped due to conflicts. Resolve conflicts and use git_rebase_continue.");
				case FAILED -> failure("Rebase failed: " + (result.getFailingPaths() != null
						? String.join(", ", result.getFailingPaths().keySet()) : "Unknown error"));
				default -> failure("Rebase ended with status: " + result.getStatus());
			};

		}
		catch (GitAPIException e) {
			logger.error("Error skipping rebase", e);
			return failure("Error skipping rebase: " + e.getMessage());
		}
	}

	/**
	 * Aborts an interactive rebase and returns to the original state.
	 */
	@Tool(name = "git_rebase_abort",
			description = "Aborts an interactive rebase and returns the repository to its original state before the rebase started.")
	public Result<String> abortRebase() {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();

			if (!repo.getRepositoryState().isRebasing()) {
				return failure("No rebase in progress");
			}

			var result = git.rebase().setOperation(RebaseCommand.Operation.ABORT).call();

			return switch (result.getStatus()) {
				case ABORTED -> success("Rebase aborted successfully. Repository returned to original state.");
				case FAILED -> failure("Failed to abort rebase");
				default -> failure("Unexpected result when aborting rebase: " + result.getStatus());
			};

		}
		catch (GitAPIException e) {
			logger.error("Error aborting rebase", e);
			return failure("Error aborting rebase: " + e.getMessage());
		}
	}

	/**
	 * Gets a list of commits that would be included in an interactive rebase. Uses short
	 * format by default.
	 */
	@Tool(name = "git_rebase_preview",
			description = "Previews the commits that would be included in an interactive rebase from the specified base commit. Use this to see what commits will be affected before starting the rebase. If baseCommit is null/empty, previews rebase to root (all commits). Uses short format by default.")
	public Result<List<RebaseCommit>> previewRebase(String baseCommit, int maxCount) {
		return previewRebase(baseCommit, maxCount, false, false);
	}

	/**
	 * Gets a list of commits that would be included in an interactive rebase. Display
	 * format is configurable.
	 */
	public Result<List<RebaseCommit>> previewRebase(String baseCommit, int maxCount, boolean useFullCommitId,
			boolean useFullMessage) {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		if (maxCount < 0) {
			maxCount = 0;
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();

			var baseCommitId = (ObjectId) null;

			// If base commit is specified, resolve it. If null/empty, we'll rebase to
			// root
			if (baseCommit != null && !baseCommit.trim().isEmpty()) {
				try {
					baseCommitId = repo.resolve(baseCommit);
					if (baseCommitId == null) {
						return failure("Could not resolve base commit: " + baseCommit);
					}
				}
				catch (IOException e) {
					return failure("Invalid base commit: " + baseCommit);
				}
			}

			var commits = new ArrayList<RebaseCommit>();
			try (var revWalk = new RevWalk(repo)) {
				var headId = repo.resolve("HEAD");
				if (headId == null) {
					return failure("Could not resolve HEAD");
				}

				var headCommit = revWalk.parseCommit(headId);
				revWalk.markStart(headCommit);

				// If base commit is specified, mark it as uninteresting
				if (baseCommitId != null) {
					var baseCommitObj = revWalk.parseCommit(baseCommitId);
					revWalk.markUninteresting(baseCommitObj);
				}

				int count = 0;
				for (RevCommit commit : revWalk) {
					if (maxCount > 0 && count >= maxCount) {
						break;
					}

					var commitId = useFullCommitId ? commit.getId().name() : commit.getId().abbreviate(7).name();
					var message = useFullMessage ? commit.getFullMessage() : commit.getShortMessage();

					commits
						.add(new RebaseCommit(commitId, message, commit.getAuthorIdent().getName(),
								commit.getAuthorIdent().getEmailAddress(), LocalDateTime
									.ofInstant(commit.getAuthorIdent().getWhenAsInstant(), ZoneId.systemDefault()),
								RebaseAction.PICK));
					count++;
				}
			}

			return success(commits);

		}
		catch (Exception e) {
			logger.error("Error previewing rebase", e);
			return failure("Error previewing rebase: " + e.getMessage());
		}
	}

	/**
	 * Lists commits with numeric IDs for easy reference in rebase operations. Uses short
	 * format by default.
	 */
	@Tool(name = "git_list_commits", description = "Lists current commits with numeric IDs, commit information "
			+ "(commit ID, commit message, author) for easy reference in rebase operations. Uses short format by default.")
	public Result<List<NumberedCommit>> listCommits(String baseCommit, int maxCount) {
		return listCommits(baseCommit, maxCount, false, false);
	}

	/**
	 * Lists commits with numeric IDs for easy reference in rebase operations. Display
	 * format is configurable.
	 */
	public Result<List<NumberedCommit>> listCommits(String baseCommit, int maxCount, boolean useFullCommitId,
			boolean useFullMessage) {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		if (maxCount <= 0) {
			maxCount = 10; // Default to 10 commits
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();

			ObjectId baseCommitId = null;

			// If no base commit specified, we'll list from the beginning or use maxCount
			if (baseCommit != null && !baseCommit.trim().isEmpty()) {
				try {
					baseCommitId = repo.resolve(baseCommit);
					if (baseCommitId == null) {
						return failure("Could not resolve base commit: " + baseCommit);
					}
				}
				catch (IOException e) {
					return failure("Invalid base commit: " + baseCommit);
				}
			}

			var commits = new ArrayList<NumberedCommit>();
			try (var revWalk = new RevWalk(repo)) {
				var headId = repo.resolve("HEAD");
				if (headId == null) {
					return failure("Could not resolve HEAD");
				}

				var headCommit = revWalk.parseCommit(headId);
				revWalk.markStart(headCommit);

				// If base commit is specified, mark it as uninteresting
				if (baseCommitId != null) {
					var baseCommitObj = revWalk.parseCommit(baseCommitId);
					revWalk.markUninteresting(baseCommitObj);
				}

				int count = 0;
				int id = 1;
				for (RevCommit commit : revWalk) {
					if (count >= maxCount) {
						break;
					}

					var commitId = useFullCommitId ? commit.getId().name() : commit.getId().abbreviate(7).name();
					var message = useFullMessage ? commit.getFullMessage() : commit.getShortMessage();

					commits.add(new NumberedCommit(id++, commitId, message, commit.getAuthorIdent().getName()));
					count++;
				}
			}

			return success(commits);

		}
		catch (Exception e) {
			logger.error("Error listing commits", e);
			return failure("Error listing commits: " + e.getMessage());
		}
	}

	/**
	 * Performs a simplified interactive rebase based on instructions.
	 */
	@Tool(name = "git_simple_rebase",
			description = "Performs an interactive rebase with simple instructions. Use RebaseAction enum values: PICK (keep), SQUASH (combine with previous), DROP (remove), REWORD (change message), EDIT (stop for editing), FIXUP (like squash but discard message). Specify commit order and actions. If baseCommit is null/empty, rebases to root.")
	public Result<RebaseExecutionResult> performSimpleRebase(String baseCommit, List<RebaseInstruction> instructions) {
		if (!repository.isHasRepo()) {
			return failure("Repository is not open");
		}

		if (instructions == null || instructions.isEmpty()) {
			return failure("Rebase instructions are required");
		}

		try (var git = new Git(repository.getRepository())) {
			var repo = git.getRepository();

			if (repo.getRepositoryState().isRebasing()) {
				return failure(
						"A rebase is already in progress. Use git_rebase_continue, git_rebase_abort, or git_rebase_skip.");
			}

			// First, get the list of commits to validate instructions
			var commitsResult = listCommits(baseCommit, 50, false, false);
			if (commitsResult instanceof Result.Failure<List<NumberedCommit>>(String message)) {
				return failure("Failed to get commits: " + message);
			}

			var availableCommits = ((Result.Success<List<NumberedCommit>>) commitsResult).value();

			// Validate instructions
			for (RebaseInstruction instruction : instructions) {
				boolean found = availableCommits.stream().anyMatch(commit -> commit.id() == instruction.commitId());
				if (!found) {
					return failure("Invalid commit ID: " + instruction.commitId());
				}

				if (instruction.action() == null) {
					return failure("Action is required for commit ID: " + instruction.commitId());
				}
			}

			ObjectId baseCommitId;
			var isRootRebase = (baseCommit == null || baseCommit.trim().isEmpty());

			if (!isRootRebase) {
				try {
					baseCommitId = repo.resolve(baseCommit);
					if (baseCommitId == null) {
						return failure("Could not resolve base commit: " + baseCommit);
					}
				}
				catch (IOException e) {
					return failure("Invalid base commit: " + baseCommit);
				}
			}
			else {
				// For root rebase, find the root commit and use it as base
				// This allows rebasing all commits including the root
				try (var revWalk = new RevWalk(repo)) {
					var headId = repo.resolve("HEAD");
					if (headId == null) {
						return failure("Could not resolve HEAD");
					}

					var headCommit = revWalk.parseCommit(headId);
					revWalk.markStart(headCommit);

					RevCommit rootCommit = null;
					for (RevCommit commit : revWalk) {
						rootCommit = commit; // The last commit in the walk is the root
					}

					if (rootCommit == null) {
						return failure("Could not find root commit");
					}

					// Use the root commit itself as the base for rebasing
					// This will include all commits in the rebase
					baseCommitId = rootCommit.getId();
				}
				catch (IOException e) {
					return failure("Error finding root commit: " + e.getMessage());
				}
			}

			// Create a custom interactive handler that applies our instructions
			var rebaseCommand = git.rebase()
				.setUpstream(baseCommitId)
				.runInteractively(new org.eclipse.jgit.api.RebaseCommand.InteractiveHandler() {
					@Override
					public void prepareSteps(List<org.eclipse.jgit.lib.RebaseTodoLine> steps) {
						// Map our instructions to JGit's rebase steps
						for (org.eclipse.jgit.lib.RebaseTodoLine step : steps) {
							// Find matching instruction by commit ID
							var commitId = step.getCommit().name();
							RebaseInstruction matchingInstruction = null;

							for (RebaseInstruction instruction : instructions) {
								var numberedCommit = availableCommits.stream()
									.filter(c -> c.id() == instruction.commitId())
									.findFirst()
									.orElse(null);

								if (numberedCommit != null && numberedCommit.commitId().equals(commitId)) {
									matchingInstruction = instruction;
									break;
								}
							}

							if (matchingInstruction != null) {
								var action = matchingInstruction.action().toJGitAction();

								try {
									step.setAction(action);
								}
								catch (org.eclipse.jgit.errors.IllegalTodoFileModification e) {
									logger.warn("Could not set action {} for commit {}: {}", action, commitId,
											e.getMessage());
								}
							}
						}
					}

					@Override
					public String modifyCommitMessage(String commit) {
						// Find if there's a reword instruction for this commit
						for (RebaseInstruction instruction : instructions) {
							if (instruction.action() == RebaseAction.REWORD && instruction.newMessage() != null
									&& !instruction.newMessage().trim().isEmpty()) {
								return instruction.newMessage();
							}
						}
						return commit;
					}
				});

			var result = rebaseCommand.call();

			return switch (result.getStatus()) {
				case OK -> success(new RebaseExecutionResult(true, false, "Rebase completed successfully", List.of()));
				case STOPPED -> success(new RebaseExecutionResult(false, false,
						"Rebase stopped for editing. Use git_rebase_continue when ready.", List.of()));
				case CONFLICTS -> {
					var conflictedFiles = result.getConflicts() != null ? new ArrayList<>(result.getConflicts())
							: List.<String>of();
					yield success(new RebaseExecutionResult(false, true,
							"Rebase stopped due to conflicts. Resolve conflicts and use git_rebase_continue.",
							conflictedFiles));
				}
				case UNCOMMITTED_CHANGES -> failure("Cannot start rebase with uncommitted changes");
				case FAILED -> failure("Rebase failed: " + (result.getFailingPaths() != null
						? String.join(", ", result.getFailingPaths().keySet()) : "Unknown error"));
				default -> failure("Rebase ended with status: " + result.getStatus());
			};

		}
		catch (GitAPIException e) {
			logger.error("Error performing simple rebase", e);
			return failure("Error performing simple rebase: " + e.getMessage());
		}
		catch (Exception e) {
			logger.error("Unexpected error during simple rebase", e);
			return failure("Unexpected error during simple rebase: " + e.getMessage());
		}
	}

}

/**
 * Manages configuration settings for Git repositories. Provides methods to read and
 * update Git repository configuration.
 */
class GitRepositoryHolder {

	private final Logger logger = LoggerFactory.getLogger(GitRepositoryHolder.class);

	private final Repository repository;

	private Config config;

	/**
	 * Creates a new Git repository configuration manager.
	 * @param repository JGit repository
	 */
	public GitRepositoryHolder(Repository repository) {
		this.repository = repository;
		this.config = repository.getConfig();
	}

	/**
	 * Reloads the configuration from the repository.
	 */
	public void reload() {
		this.config = repository.getConfig();
		logger.debug("Reloaded configuration for repository: {}", repository.getDirectory());
	}

	/**
	 * Gets a configuration value.
	 * @param section Configuration section
	 * @param name Configuration name
	 * @return Configuration value or null if not found
	 */
	public String getString(String section, String name) {
		return config.getString(section, null, name);
	}

	/**
	 * Gets a configuration value.
	 * @param section Configuration section
	 * @param subsection Configuration subsection
	 * @param name Configuration name
	 * @return Configuration value or null if not found
	 */
	public String getString(String section, String subsection, String name) {
		return config.getString(section, subsection, name);
	}

	/**
	 * Sets a configuration value.
	 * @param section Configuration section
	 * @param name Configuration name
	 * @param value Configuration value
	 */
	public void setString(String section, String name, String value) {
		config.setString(section, null, name, value);
		logger.debug("Set configuration {}={} for section {}", name, value, section);
	}

	/**
	 * Sets a configuration value.
	 * @param section Configuration section
	 * @param subsection Configuration subsection
	 * @param name Configuration name
	 * @param value Configuration value
	 */
	public void setString(String section, String subsection, String name, String value) {
		config.setString(section, subsection, name, value);
		logger.debug("Set configuration {}={} for section {}.{}", name, value, section, subsection);
	}

	/**
	 * Gets a boolean configuration value.
	 * @param section Configuration section
	 * @param name Configuration name
	 * @return Configuration value or false if not found
	 */
	public boolean getBoolean(String section, String name) {
		return config.getBoolean(section, null, name, false);
	}

	/**
	 * Gets a boolean configuration value.
	 * @param section Configuration section
	 * @param subsection Configuration subsection
	 * @param name Configuration name
	 * @return Configuration value or false if not found
	 */
	public boolean getBoolean(String section, String subsection, String name) {
		return config.getBoolean(section, subsection, name, false);
	}

	/**
	 * Sets a boolean configuration value.
	 * @param section Configuration section
	 * @param name Configuration name
	 * @param value Configuration value
	 */
	public void setBoolean(String section, String name, boolean value) {
		config.setBoolean(section, null, name, value);
		logger.debug("Set configuration {}={} for section {}", name, value, section);
	}

	/**
	 * Sets a boolean configuration value.
	 * @param section Configuration section
	 * @param subsection Configuration subsection
	 * @param name Configuration name
	 * @param value Configuration value
	 */
	public void setBoolean(String section, String subsection, String name, boolean value) {
		config.setBoolean(section, subsection, name, value);
		logger.debug("Set configuration {}={} for section {}.{}", name, value, section, subsection);
	}

	/**
	 * Gets all configuration values for a section.
	 * @param section Configuration section
	 * @return Map of configuration names to values
	 */
	public Map<String, String> getSection(String section) {
		var result = new HashMap<String, String>();
		var names = config.getNames(section);
		for (String name : names) {
			result.put(name, config.getString(section, null, name));
		}
		return result;
	}

	/**
	 * Gets all configuration values for a section and subsection.
	 * @param section Configuration section
	 * @param subsection Configuration subsection
	 * @return Map of configuration names to values
	 */
	public Map<String, String> getSection(String section, String subsection) {
		var result = new HashMap<String, String>();
		var names = config.getNames(section, subsection);
		for (String name : names) {
			result.put(name, config.getString(section, subsection, name));
		}
		return result;
	}

	/**
	 * Saves the configuration changes to the repository.
	 * @return True if the configuration was saved successfully, false otherwise
	 */
	public boolean save() {
		try {
			// In JGit, we need to save the config to the repository's config file
			repository.getConfig().save();
			logger.info("Saved configuration for repository: {}", repository.getDirectory());
			return true;
		}
		catch (IOException e) {
			logger.error("Failed to save configuration for repository: {}", repository.getDirectory(), e);
			return false;
		}
	}

	/**
	 * Gets the user name configured for the repository.
	 * @return User name or null if not configured
	 */
	public String getUserName() {
		return getString("user", "name");
	}

	/**
	 * Sets the user name for the repository.
	 * @param name User name
	 */
	public void setUserName(String name) {
		setString("user", "name", name);
	}

	/**
	 * Gets the user email configured for the repository.
	 * @return User email or null if not configured
	 */
	public String getUserEmail() {
		return getString("user", "email");
	}

	/**
	 * Sets the user email for the repository.
	 * @param email User email
	 */
	public void setUserEmail(String email) {
		setString("user", "email", email);
	}

	/**
	 * Gets the default branch configured for the repository.
	 * @return Default branch or "master" if not configured
	 */
	public String getDefaultBranch() {
		return getString("init", "defaultBranch");
	}

	/**
	 * Sets the default branch for the repository.
	 * @param branch Default branch
	 */
	public void setDefaultBranch(String branch) {
		setString("init", "defaultBranch", branch);
	}

}
