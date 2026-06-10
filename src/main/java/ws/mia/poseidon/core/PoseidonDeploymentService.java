package ws.mia.poseidon.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ws.mia.phoenix.api.PhoenixClient;
import ws.mia.phoenix.api.model.Route;
import ws.mia.poseidon.api.model.PoseidonDeploymentPayload;
import ws.mia.poseidon.core.docker.DockerService;
import ws.mia.poseidon.core.env.EnvironmentService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
public class PoseidonDeploymentService {

	private static final Logger log = LoggerFactory.getLogger(PoseidonDeploymentService.class);
	private final DockerService dockerService;
	private final EnvironmentService environmentService;
	private final PhoenixClient phoenixClient;
	private final ObjectMapper objectMapper;

	public PoseidonDeploymentService(DockerService dockerService, @Qualifier("defaultEnvironmentService") EnvironmentService environmentService, PhoenixClient phoenixClient, ObjectMapper objectMapper) {
		this.dockerService = dockerService;
		this.environmentService = environmentService;
		this.phoenixClient = phoenixClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * Attempts to deploy a Poseidon Payload.
	 * If successful, returns true.
	 */
	public boolean deploy(PoseidonDeploymentPayload payload) {
		log.info("Attempting to deploy {}", payload.getRepository() + ":" + payload.getBranch());

		PoseidonDockerfileLabels dockerfileLabels = PoseidonDockerfileLabels.fromLabelMap(dockerService.fetchLabels(payload.getImage()));

		String containerName = payload.getRepositoryName() + "_" + payload.getBranch();

		Optional<Integer> externalPort = Optional.empty();
		Map<Integer, Integer> boundPorts = new HashMap<>();

		if (dockerfileLabels.getInternalPort().isPresent()) {
			externalPort = Optional.of(generateExternalPort(dockerfileLabels.isPhoenixInstance()));
			boundPorts.put(dockerfileLabels.getInternalPort().get(), externalPort.get());
		}

		Map<String, String> secrets = null;
		try {
			secrets = fetchSecrets(payload);
		} catch (IOException e) {
			throw new RuntimeException(e); // be safe, secrets are important.
		}

		Map<String, String> injectedLabels = getDeploymentLabels(payload);

		dockerService.deployContainer(payload.getImage(), containerName, boundPorts, secrets, injectedLabels);

		if (dockerfileLabels.isPhoenixInstance()) {
			return true;
		}

		updatePhoenixRoute(dockerfileLabels.getPhoenixSource(payload.getBranch()),
				dockerfileLabels.getPhoenixAliases(payload.getBranch()).stream().toList(),
				externalPort);
		return true;
	}

	private void updatePhoenixRoute(Optional<String> phoenixSource, List<String> aliases, Optional<Integer> externalPort) {
		if (externalPort.isEmpty() || phoenixSource.isEmpty()) {
			phoenixSource.ifPresent(source -> {
				phoenixClient.removeRoute(source);
				log.info("Removed phoenix route {}", source);
			});
			return;
		}

		Route newRoute = new Route.Builder()
				.source(phoenixSource.get())
				.aliases(aliases)
				.destination(dockerService.getDockerHost(true) + ":" + externalPort.get())
				.build();

		if (!phoenixClient.routeExists(phoenixSource.get())) {
			phoenixClient.pushRoute(newRoute);
			log.info("Created phoenix route {}", phoenixSource.get());
			return;
		}

		phoenixClient.modifyRoute(phoenixSource.get(),
				new Route.Builder().from(phoenixClient.getRoute(phoenixSource.get()).orElseThrow())
						.aliases(newRoute.getAliases())
						.destination(newRoute.getDestination()).build()
		);

		log.info("Modified phoenix route {}", phoenixSource.get());
	}

	private Map<String, String> getDeploymentLabels(PoseidonDeploymentPayload payload) {
		Map<String, Object> raw = objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {
		});

		return raw.entrySet().stream()
				.filter(e -> e.getValue() != null)
				.collect(Collectors.toMap(
						e -> "deployment." + e.getKey(),
						e -> e.getValue().toString()
				));
	}

	private int generateExternalPort(boolean phoenixSelf) {
		if (phoenixSelf) {
			// we keep a certain port reserved for phoenix so that incoming requests can easily be routed to it
			return environmentService.getPhoenixPort();
		}

		int port = ThreadLocalRandom.current().nextInt(20000, 40000);
		if (phoenixClient.getRoutes().stream().anyMatch(route -> route.getDestination().equals(dockerService.getDockerHost(true) + ":" + port))) {
			return generateExternalPort(false);
		}

		return port;
	}

	private Map<String, String> fetchSecrets(PoseidonDeploymentPayload payload) throws IOException {
		Map<String, String> mergedSecrets = new HashMap<>();

		// /secrets/repo.env
		File wildcardFile = new File(environmentService.getSecretDirectory() + "/" + payload.getRepositoryName() + ".env");

		// /secrets/repo/branch.env
		File branchFile = new File(environmentService.getSecretDirectory() + "/" + payload.getRepositoryName() + "/" + payload.getBranch() + ".env");

		if (wildcardFile.exists()) {
			mergedSecrets.putAll(parseEnvFile(wildcardFile));
		}

		if (branchFile.exists()) {
			mergedSecrets.putAll(parseEnvFile(branchFile)); // Map#putAll overrides existing keys
		}

		return mergedSecrets;
	}

	private Map<String, String> parseEnvFile(File envFile) throws IOException {
		Map<String, String> retVars = new HashMap<>();

		List<String> fileLines = Files.readAllLines(envFile.toPath());
		for (int i = 0; i < fileLines.size(); i++) {
			String line = fileLines.get(i);
			line = line.trim();
			if (!line.isEmpty() && !line.startsWith("#")) {

				if (!line.contains("=")) {
					// While skipping may be easy, this could be a misconfigured external API key or something, throwing is *far* safer.
					throw new IOException("Tried to read invalid secrets file %s (missing = on line %s)".formatted(envFile.toPath(), i + 1));
				}

				String[] splitLine = line.split("=", 2);
				retVars.put(splitLine[0], splitLine[1]);
			}
		}

		return retVars;
	}

}
