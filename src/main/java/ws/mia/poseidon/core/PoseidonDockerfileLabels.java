package ws.mia.poseidon.core;

import jakarta.annotation.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Models Dockerfile labels which Poseidon may consume
 */
public class PoseidonDockerfileLabels {

	/// An internal port which is forwarded out.
	/// If any Phoenix labels are used, this must be set.
	///
	/// Interal port is specified through `internal-port`
	@Nullable
	private Integer internalPort;

	/// Phoenix aliases keyed by branch. a null key signifies fallback aliases.
	///
	/// Aliases for branches may be specified through a singular alias in `phoenix.alias.{branch}`,
	/// or a comma-separated list via `phoenix.aliases.{branch}`.
	///
	/// Fallback aliases may be specified through a singular alias in `phoenix.alias`,
	/// or a comma-separated list via `phoenix.aliases`
	private Map<String, Set<String>> phoenixAliases;

	/// Specifies whether this deployment *is* the Phoenix reverse proxy.
	///
	/// set if `phoenix.self` is `"true"`
	private boolean phoenixSelf;

	/// Phoenix sources keyed by branch. A null key signifies a fallback source.
	///
	/// A source is specified through `phoenix.source.{branch}`.
	/// A fallback source may be specified by setting `phoenix.source`
	private Map<String, String> phoenixSources;

	private PoseidonDockerfileLabels(@Nullable Integer internalPort, Map<String, Set<String>> phoenixAliases, boolean phoenixSelf, Map<String, String> phoenixSources) {
		this.internalPort = internalPort;
		this.phoenixAliases = phoenixAliases;
		this.phoenixSelf = phoenixSelf;
		this.phoenixSources = phoenixSources;
	}

	public static PoseidonDockerfileLabels fromLabelMap(Map<String, String> labels) throws IllegalArgumentException {
		Integer internalPort = fetchInternalPort(labels).orElse(null);
		boolean phoenixSelf = fetchPhoenixSelf(labels);
		Map<String, String> sources = fetchSources(labels);
		Map<String, Set<String>> aliases = fetchAliases(labels);

		if (internalPort == null && (!aliases.isEmpty() || !sources.isEmpty())) {
			throw new IllegalArgumentException("internal-port Dockerfile label must be set when using Phoenix labels");
		}

		// if an alias for a branch exists, the branch must specify its source.
		aliases.keySet().forEach(aliasBranch -> {
			if(!sources.containsKey(aliasBranch)) {
				if(aliasBranch != null) {
					throw new IllegalArgumentException("Found aliases for branch '%s' without a corresponding source".formatted(aliasBranch));
				}
				throw new IllegalArgumentException("Found fallback aliases without fallback source");
			}
		});

		return new PoseidonDockerfileLabels(internalPort, aliases, phoenixSelf, sources);
	}

	private static Optional<Integer> fetchInternalPort(Map<String, String> labels) throws NumberFormatException {
		String internalPortStr = labels.get("internal-port");
		if (internalPortStr != null) {
			return Integer.valueOf(internalPortStr).describeConstable(); // if it's not an int, we just error out
		}

		return Optional.empty();
	}

	private static boolean fetchPhoenixSelf(Map<String, String> labels) {
		String pfoxSelfStr = labels.get("phoenix.self");
		if (pfoxSelfStr != null) {
			return Boolean.parseBoolean(pfoxSelfStr);
		}

		return false;
	}

	private static Map<String, String> fetchSources(Map<String, String> labels) {
		Map<String, String> retBranchSources = new HashMap<>();

		labels.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("phoenix.source."))
				.forEach(entry -> {
					String[] split = entry.getKey().split("\\.", 3);
					// some thoughts for validation:
					// while having two branches deploy to the same source seems dumb, I don't think there's any good reason to guard against it.
					if (!split[2].isBlank()) retBranchSources.put(split[2], entry.getValue());
				});


		String fallbackRoute = labels.get("phoenix.source");
		if (fallbackRoute != null) {
			retBranchSources.put(null, fallbackRoute);
		}

		return retBranchSources;
	}

	private static Map<String, Set<String>> fetchAliases(Map<String, String> labels) {
		Map<String, Set<String>> aliases = new HashMap<>();

		// for comma-separated phoenix.aliases
		Function<String, Set<String>> separateAliases = (aliasesStr) ->
				Arrays.stream(aliasesStr.split(","))
						.filter(Predicate.not(String::isBlank))
						.map(String::trim).collect(Collectors.toSet());

		BiConsumer<String, String> putAlias = (branch, alias) -> {
			if (alias.isBlank()) return;
			branch = branch != null ? branch.trim() : null;
			Set<String> newSet = aliases.getOrDefault(branch, new HashSet<>());
			newSet.add(alias);
			aliases.put(branch, newSet);
		};

		// fallback
		String fallbackAlias = labels.get("phoenix.alias");
		if (fallbackAlias != null) {
			putAlias.accept(null, fallbackAlias);
		}

		String fallbackAliasesStr = labels.get("phoenix.aliases");
		if (fallbackAliasesStr != null) {
			separateAliases.apply(fallbackAliasesStr).forEach(a -> putAlias.accept(null, a));
		}

		labels.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("phoenix.alias."))
				.forEach(entry -> {
					String[] split = entry.getKey().split("\\.", 3);
					if (split[2].isBlank()) return;
					putAlias.accept(split[2], entry.getValue());
				});

		labels.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("phoenix.aliases."))
				.forEach(entry -> {
					String[] split = entry.getKey().split("\\.", 3);
					if (split[2].isBlank()) return;
					separateAliases.apply(entry.getValue()).forEach(alias -> putAlias.accept(split[2], alias));
				});

		return aliases;
	}


	public boolean isPhoenixInstance() {
		return phoenixSelf;
	}

	public Optional<Integer> getInternalPort() {
		return Optional.ofNullable(internalPort);
	}

	public Optional<String> getPhoenixSource(String branch) {
		return Optional.ofNullable(phoenixSources.getOrDefault(branch, phoenixSources.getOrDefault(null, null)));
	}

	public Set<String> getPhoenixAliases(String branch) {
		Set<String> branchAliases = phoenixAliases.get(branch);
		if(branchAliases != null) return branchAliases;

		Set<String> fallbackAliases = phoenixAliases.get(null);
		if(fallbackAliases != null) return fallbackAliases;

		return Set.of();
	}

}




