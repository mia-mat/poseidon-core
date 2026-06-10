package ws.mia.poseidon.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PoseidonDockerfileLabelsTest {

	// ── fromLabelMap: internalPort ────────────────────────────────────────────

	@Test
	void parsesInternalPort() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of("internal-port", "8080"));
		assertEquals(Optional.of(8080), result.getInternalPort());
	}

	@Test
	void internalPortAbsentWhenLabelMissing() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of());
		assertEquals(Optional.empty(), result.getInternalPort());
	}

	@Test
	void throwsOnNonIntegerInternalPort() {
		assertThrows(NumberFormatException.class,
				() -> PoseidonDockerfileLabels.fromLabelMap(Map.of("internal-port", "notanumber")));
	}

	// ── fromLabelMap: phoenixSelf ─────────────────────────────────────────────

	@Test
	void phoenixSelfFalseByDefault() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of());
		assertFalse(result.isPhoenixInstance());
	}

	@Test
	void phoenixSelfTrueWhenLabelIsTrue() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of("phoenix.self", "true"));
		assertTrue(result.isPhoenixInstance());
	}

	@Test
	void phoenixSelfTrueWhenLabelIsTrueIgnoreCase() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of("phoenix.self", "tRuE"));
		assertTrue(result.isPhoenixInstance());
	}

	@Test
	void phoenixSelfFalseWhenLabelIsFalse() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of("phoenix.self", "false"));
		assertFalse(result.isPhoenixInstance());
	}

	@Test
	void phoenixSelfFalseWhenLabelIsOther() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of("phoenix.self", "else"));
		assertFalse(result.isPhoenixInstance());
	}

	// ── fromLabelMap: validation ──────────────────────────────────────────────

	@Test
	void throwsWhenSourceUsedWithoutInternalPort() {
		var labels = Map.of("phoenix.source", "myapp.example.com");
		assertThrows(IllegalArgumentException.class,
				() -> PoseidonDockerfileLabels.fromLabelMap(labels));
	}

	@Test
	void throwsWhenAliasUsedWithoutInternalPort() {
		var labels = Map.of("phoenix.alias", "myapp.example.com");
		assertThrows(IllegalArgumentException.class,
				() -> PoseidonDockerfileLabels.fromLabelMap(labels));
	}

	@Test
	void throwsWhenBranchAliasHasNoCorrespondingSource() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias.main", "myapp.example.com"
				// no phoenix.source.main
		);
		assertThrows(IllegalArgumentException.class,
				() -> PoseidonDockerfileLabels.fromLabelMap(labels));
	}

	@Test
	void throwsWhenFallbackAliasHasNoFallbackSource() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias", "myapp.example.com"
				// no phoenix.source
		);
		assertThrows(IllegalArgumentException.class,
				() -> PoseidonDockerfileLabels.fromLabelMap(labels));
	}

	@Test
	void doesNotThrowWhenBranchAliasHasMatchingSource() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias.main", "myapp.example.com",
				"phoenix.source.main", "myapp.example.com"
		);
		assertDoesNotThrow(() -> PoseidonDockerfileLabels.fromLabelMap(labels));
	}

	@Test
	void doesNotThrowWithNoPhoenixLabels() {
		assertDoesNotThrow(() -> PoseidonDockerfileLabels.fromLabelMap(Map.of()));
	}

	// ── getPhoenixSource ──────────────────────────────────────────────────────

	@Test
	void returnsBranchSpecificSource() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.source.main", "main.example.com",
				"phoenix.source.dev", "dev.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Optional.of("main.example.com"), result.getPhoenixSource("main"));
		assertEquals(Optional.of("dev.example.com"), result.getPhoenixSource("dev"));
	}

	@Test
	void fallsBackToFallbackSourceWhenBranchNotFound() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.source", "fallback.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Optional.of("fallback.example.com"), result.getPhoenixSource("main"));
	}

	@Test
	void prefersBranchSourceOverFallbackSource() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.source.main", "main.example.com",
				"phoenix.source", "fallback.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Optional.of("main.example.com"), result.getPhoenixSource("main"));
	}

	@Test
	void returnsEmptyWhenNoSourceAtAll() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of());
		assertEquals(Optional.empty(), result.getPhoenixSource("main"));
	}

	// ── getPhoenixAliases ─────────────────────────────────────────────────────

	@Test
	void returnsBranchSpecificAlias() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias.main", "main.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("main.example.com"), result.getPhoenixAliases("main"));
	}

	@Test
	void fallsBackToFallbackAliasesWhenBranchNotFound() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias", "fallback.example.com",
				"phoenix.source", "fallback.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("fallback.example.com"), result.getPhoenixAliases("main"));
	}

	@Test
	void prefersBranchAliasesOverFallbackAliases() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias.main", "main.example.com",
				"phoenix.source.main", "main.example.com",
				"phoenix.alias", "fallback.example.com",
				"phoenix.source", "fallback.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("main.example.com"), result.getPhoenixAliases("main"));
	}

	@Test
	void returnsEmptySetWhenNoAliasesAtAll() {
		var result = PoseidonDockerfileLabels.fromLabelMap(Map.of());
		assertEquals(Set.of(), result.getPhoenixAliases("main"));
	}

	@Test
	void parsesCommaSeparatedAliases() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.aliases.main", "a.example.com,b.example.com,c.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com", "c.example.com"),
				result.getPhoenixAliases("main"));
	}

	@Test
	void parsesCommaSeparatedFallbackAliases() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.aliases", "a.example.com,b.example.com",
				"phoenix.source", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com"),
				result.getPhoenixAliases("anybranch"));
	}

	@Test
	void commaSeparatedAliasesIgnoreBlankEntries() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.aliases.main", "a.example.com,,b.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com"),
				result.getPhoenixAliases("main"));
	}

	@Test
	void mergesSingularAndPluralAliasLabels() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias.main", "a.example.com",
				"phoenix.aliases.main", "b.example.com,c.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com", "c.example.com"),
				result.getPhoenixAliases("main"));
	}

	@Test
	void ignoresDuplicateSingularAndPluralAliasLabels() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.alias.main", "a.example.com",
				"phoenix.aliases.main", "a.example.com, b.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com"),
				result.getPhoenixAliases("main"));
	}

	@Test
	void parsesSpaceSeparatedAliases() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.aliases.main", "a.example.com b.example.com c.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com", "c.example.com"),
				result.getPhoenixAliases("main"));
	}

	@Test
	void parsesSpaceSeparatedFallbackAliases() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.aliases", "a.example.com b.example.com",
				"phoenix.source", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com"),
				result.getPhoenixAliases("anybranch"));
	}

	@Test
	void spaceSeparatedAliasesIgnoreExtraWhitespace() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.aliases.main", "a.example.com   b.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com"),
				result.getPhoenixAliases("main"));
	}

	@Test
	void parsesMixedCommaAndSpaceSeparatedAliases() {
		var labels = Map.of(
				"internal-port", "8080",
				"phoenix.aliases.main", "a.example.com b.example.com,c.example.com",
				"phoenix.source.main", "main.example.com"
		);
		var result = PoseidonDockerfileLabels.fromLabelMap(labels);
		assertEquals(Set.of("a.example.com", "b.example.com", "c.example.com"),
				result.getPhoenixAliases("main"));
	}

}