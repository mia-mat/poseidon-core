package ws.mia.poseidon.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ws.mia.poseidon.core.env.EnvironmentService;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class DockerService {

	private static final Logger log = LoggerFactory.getLogger(DockerService.class);
	private final DockerClient dockerClient;
	private final EnvironmentService environmentService;

	public DockerService(DockerClient dockerClient, EnvironmentService environmentService) {
		this.dockerClient = dockerClient;
		this.environmentService = environmentService;
	}

	public String getDockerHost(boolean withScheme) {
		return (withScheme ? "http://" : "") + "host.docker.internal";
	}

	/**
	 * Extract Dockerfile labels from a Docker image
	 */
	public Map<String, String> fetchLabels(String imageUrl) throws NotFoundException {
		ensureImageExists(imageUrl);
		ContainerConfig c = dockerClient.inspectImageCmd(imageUrl).exec().getConfig();
		return c != null ? c.getLabels() : Map.of();
	}

	/**
	 * Deploys a Docker container with a name, bound ports, secrets, and labels.
	 * If a container with the given name already exists, overrides the existing container.
	 *
	 * @param boundPorts As a map of internal:external ports
	 * @return Docker container ID of the created or redeployed container
	 * @throws NotFoundException
	 */
	public String deployContainer(String imageUrl, String containerName, Map<Integer, Integer> boundPorts, Map<String, String> secrets, Map<String, String> labels) throws NotFoundException {
		// TODO give a volume for persistent storage -> (.withVolumes)
		ensureImageExists(imageUrl);

		if (containerExists(containerName)) {
			log.info("Removing existing container: {}", containerName);
			dockerClient.removeContainerCmd(containerName)
					.withForce(true)
					.exec();
		}

		// Bind Ports
		HostConfig hostConfig = HostConfig.newHostConfig()
				.withExtraHosts(getDockerHost(false) + ":host-gateway")
				.withRestartPolicy(RestartPolicy.unlessStoppedRestart());

		Ports portBindings = new Ports();
		boundPorts.forEach((internal, external) -> {
			portBindings.bind(ExposedPort.tcp(internal), Ports.Binding.bindPort(external));
		});

		if (!portBindings.getBindings().isEmpty()) {
			hostConfig.withPortBindings(portBindings);
		}

		CreateContainerCmd createCmd = dockerClient
				.createContainerCmd(imageUrl)
				.withName(containerName)
				.withHostConfig(hostConfig);

		createCmd.withExposedPorts(boundPorts.keySet().stream().map(ExposedPort::tcp).toList());

		// docker mandates a List<String> for secrets, with each string being in the format KEY=SECRET
		if (!secrets.isEmpty()) {
			createCmd.withEnv(secrets.entrySet().stream().map(entry -> {
				return entry.getKey() + "=" + entry.getValue();
			}).toList());
		}


		createCmd.withLabels(labels);

		String containerId = createCmd.exec().getId();
		dockerClient.startContainerCmd(containerId).exec();

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

	/**
	 * Throws a NotFoundException if `imageUrl` does not point to a valid Docker image
	 */
	private void ensureImageExists(String imageUrl) {
		try {
			dockerClient.inspectImageCmd(imageUrl).exec();
		} catch (NotFoundException e) {
			pullImageWithAuth(imageUrl);
		}
	}

	/**
	 * Queries if a Docker container exists, by name
	 */
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


}
