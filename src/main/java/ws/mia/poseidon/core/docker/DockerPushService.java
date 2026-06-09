package ws.mia.poseidon.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ws.mia.poseidon.api.model.PoseidonDeploymentPayload;
import ws.mia.poseidon.core.env.EnvironmentService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@Service
public class DockerPushService {

	private static final Logger log = LoggerFactory.getLogger(DockerPushService.class);
	private final DockerClient dockerClient;
	private final EnvironmentService environmentService;

	public DockerPushService(DockerClient dockerClient, EnvironmentService environmentService) {
		this.dockerClient = dockerClient;
		this.environmentService = environmentService;
	}

	public Optional<Integer> getInternalPortLabel(final Map<String, String> labels) throws NumberFormatException {
		String internalPortStr = labels.get("internal-port");
		if(internalPortStr != null) {
			return Integer.valueOf(internalPortStr).describeConstable(); // if it's not an int, we just error out
		}

		return Optional.empty();
	}

	public Optional<String> getPhoenixSourceLabel(final Map<String, String> labels) {
		return Optional.ofNullable(labels.get("phoenix.source"));
	}

	public boolean isPhoenixSelf(final Map<String, String> labels) {
		String pfoxSelfStr = labels.get("phoenix.self");
		if(pfoxSelfStr != null) {
			return Boolean.parseBoolean(pfoxSelfStr);
		}

		return false;
	}

	public List<String> getPhoenixAliases(final Map<String, String> labels) {
		// aliases are stored under phoenix.alias, phoenix.alias.*,
		// or as a space delimited list phoenix.aliases
		List<String> aliases = new ArrayList<>();

		labels.forEach((label, value) -> {
			if(label.equals("phoenix.alias")) {
				aliases.add(value);
				return;
			}
			if(label.startsWith("phoenix.alias.") && label.lastIndexOf(".") == "phoenix.alias.owo".lastIndexOf(".")) {
				aliases.add(value);
				return;
			}
			if(label.equals("phoenix.aliases")) {
				aliases.addAll(List.of(value.split(" ")));
			}

		});

		return aliases;
	}

	public String getDockerHost(boolean withScheme) {
		return (withScheme ? "http://" : "") + "host.docker.internal";
	}

	public Map<String, String> extractDockerfileLabels(String imageUrl) throws NotFoundException {
		ensureImageExists(imageUrl);
		ContainerConfig c = dockerClient.inspectImageCmd(imageUrl).exec().getConfig();
		return c != null ? c.getLabels() : Map.of();
	}

	public String deployGHCRImage(PoseidonDeploymentPayload payload, Optional<Integer> internalPort, Optional<Integer> externalPort) throws IOException {
		// todo give a volume for persistent storage -> (.withVolumes)
		log.info("Attempting to deploy {}", payload.getRepository());

		log.info("Pulling new image {}", payload.getImage());
		ensureImageExists(payload.getImage());

		String containerName = payload.getRepositoryName();

		// stop and remove container if it exists
		if (containerExists(containerName)) {
			log.info("Removing existing container: {}", containerName);
			dockerClient.removeContainerCmd(containerName)
					.withForce(true)
					.exec();
		}

		HostConfig hostConfig = HostConfig.newHostConfig()
				.withExtraHosts(getDockerHost(false)+":host-gateway")
				.withRestartPolicy(RestartPolicy.unlessStoppedRestart());

		if (internalPort.isPresent() && externalPort.isPresent()) {
			ExposedPort exposedPort = ExposedPort.tcp(internalPort.get());
			Ports portBindings = new Ports();
			portBindings.bind(exposedPort, Ports.Binding.bindPort(externalPort.get()));
			hostConfig.withPortBindings(portBindings);
		}

		CreateContainerCmd createCmd = dockerClient
				.createContainerCmd(payload.getImage())
				.withName(containerName)
				.withHostConfig(hostConfig);

		internalPort.ifPresent(port -> createCmd.withExposedPorts(ExposedPort.tcp(port)));

		// add environment variables from env file if it exists
		File envFile = new File(environmentService.getSecretDirectory() + "/" + containerName + ".env");
		if (envFile.exists()) {
			List<String> envVars = parseEnvFile(envFile);
			log.info("Found {} secret(s) for {}", envVars.size(), payload.getRepository());
			createCmd.withEnv(envVars);
		}

		// inject labels
		Map<String, String> labels = createCmd.getLabels();
		if (labels == null) {
			labels = new HashMap<>();
		} else labels = new HashMap<>(labels); // make modifiable

		labels.put("github.repositoryOwner", payload.getRepositoryOwner());
		labels.put("github.repositoryName", payload.getRepositoryName());
		labels.put("github.repositoryId", payload.getRepositoryId());
		labels.put("github.branch", payload.getBranch());
		labels.put("github.image", payload.getImage());
		createCmd.withLabels(labels);

		// create and start container
		String containerId = createCmd.exec().getId();
		dockerClient.startContainerCmd(containerId).exec();

		log.info("Successfully deployed container {} for {}", containerName, payload.getRepositoryUrl());
		return containerId;
	}

	private void pullImageWithAuth(String imageUrl) {
		try {
			AuthConfig authConfig = new AuthConfig()
					.withUsername(environmentService.getGhcrUsername())
					.withPassword(environmentService.getGhcrToken())
					.withRegistryAddress("ghcr.io");
			dockerClient.pullImageCmd(imageUrl)
					.withAuthConfig(authConfig)
					.exec(new PullImageResultCallback())
					.awaitCompletion();

			log.info("Successfully pulled image: {}", imageUrl);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Image pull interrupted: " + imageUrl, e);
		}
	}

	private void ensureImageExists(String imageUrl) {
		try {
			dockerClient.inspectImageCmd(imageUrl).exec();
		} catch (NotFoundException e) {
			pullImageWithAuth(imageUrl);
		}
	}

	private boolean containerExists(String name) {
		try {
			List<Container> containers = dockerClient.listContainersCmd()
					.withShowAll(true)
					.withNameFilter(Collections.singletonList(name))
					.exec();
			return !containers.isEmpty();
		} catch (Exception e) {
			log.warn("Error checking if container exists: {}", name, e);
			return false;
		}
	}

	private List<String> parseEnvFile(File envFile) throws IOException {
		List<String> envVars = new ArrayList<>();
		List<String> lines = Files.readAllLines(envFile.toPath());

		for (String line : lines) {
			line = line.trim();
			if (!line.isEmpty() && !line.startsWith("#")) {
				envVars.add(line);
			}
		}

		return envVars;
	}


}
