package ws.mia.poseidon.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ws.mia.poseidon.api.model.PoseidonContainer;
import ws.mia.poseidon.api.model.PoseidonContainerEvent;
import ws.mia.poseidon.core.ServerSideEventService;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DockerPullService {

	private static final Logger log = LoggerFactory.getLogger(DockerPullService.class);

	private final DockerClient dockerClient;
	private final ServerSideEventService serverSideEventService;

	private volatile boolean listening;
	private volatile Closeable eventsCallback;

	private List<PoseidonContainer> containerCache;
	private volatile long containersLastCachedTimestamp = 0L;
	private static final long containerCacheInterval = 1000*60; // ms

	public DockerPullService(DockerClient dockerClient, ServerSideEventService serverSideEventService) {
		this.containerCache = new ArrayList<>();
		this.listening = false;
		this.dockerClient = dockerClient;
		this.serverSideEventService = serverSideEventService;
	}

	@PostConstruct
	private void listenForDockerEvents() {
		if (listening) {
			log.warn("Already listening to Docker events, skipping reconnection attempt");
			return; // Don't throw, just return
		}

		listening = true;

		EventsCmd eventsCmd = dockerClient.eventsCmd().withEventTypeFilter(EventType.CONTAINER);

		eventsCallback = eventsCmd.exec(new ResultCallback.Adapter<>() {
			@Override
			public void onNext(Event object) {
				handleDockerEvent(object);
			}

			@Override
			public void onError(Throwable throwable) {
				listening = false;
				log.error("Docker event stream error", throwable);
				try {
					Thread.sleep(5000);
					listenForDockerEvents();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.error("Reconnection interrupted", e);
				}
			}

			@Override
			public void onComplete() {
				listening = false;
				log.warn("Docker event stream completed unexpectedly");
				// Attempt to reconnect
				try {
					Thread.sleep(5000);
					listenForDockerEvents();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.error("Reconnection interrupted", e);
				}
			}

		});
	}

	private void handleDockerEvent(Event event) {
		if (event.getType() != EventType.CONTAINER) return;
		if (event.getAction() == null) return;

		containerCache.clear();

		String sseEventName;

		switch (event.getAction()) {
			case "create":
				sseEventName = "created";
				break;
			case "start":
			case "stop":
			case "restart":
			case "pause":
			case "unpause":
			case "kill":
			case "die":
				sseEventName = "updated";
				break;
			case "destroy":
				sseEventName = "deleted";
				break;
			default:
				return;
		}

		// reset container cache because we have an update
		containersLastCachedTimestamp = 0L;

		String fSseEventName = sseEventName;


		getContainer(event.getId()).ifPresentOrElse(c -> {
			serverSideEventService.sendEvent(new PoseidonContainerEvent(fSseEventName, c));
		}, () -> log.warn("Received event for unknown docker container {}", event.getId()));

	}

	public List<PoseidonContainer> getAllContainers() {
		if(containersLastCachedTimestamp > System.currentTimeMillis()-containerCacheInterval) {
			return containerCache;
		}

		try {
			List<Container> containers = dockerClient.listContainersCmd()
					.withShowAll(true)
					.exec();

			containerCache = containers.stream().map(this::poseidonFromDockerContainer).collect(Collectors.toList());
			containersLastCachedTimestamp = System.currentTimeMillis();

			return containerCache;
		} catch (Exception e) {
			log.warn("Failed to retrieve Docker containers", e);
			return List.of();
		}
	}

	private Optional<PoseidonContainer> getContainer(String containerId) {
		try {
			return getAllContainers().stream()
					.filter(c -> c.getDockerId().startsWith(containerId)) // API sometimes truncate so we use startsWith
					.findFirst();
		} catch (Exception e) {
			// ?
			return Optional.empty();
		}
	}

	private PoseidonContainer poseidonFromDockerContainer(Container dockerContainer) {
		InspectContainerResponse inspect = dockerClient.inspectContainerCmd(dockerContainer.getId()).exec();
		InspectContainerResponse.ContainerState state = inspect.getState();

		String currentStatus = state.getStatus(); // "running", "exited", etc.
		String startedAt = state.getStartedAt();  // ISO string
		String finishedAt = state.getFinishedAt(); // ISO string, may be empty if running
		Long lastStatusUpdate = null;

		try {
			Instant statusTime;
			if ("running".equals(currentStatus) && startedAt != null && !startedAt.isEmpty()) {
				statusTime = Instant.parse(startedAt);
			} else if (finishedAt != null && !finishedAt.isEmpty()) {
				statusTime = Instant.parse(finishedAt);
			} else {
				statusTime = Instant.now();
			}
			lastStatusUpdate = statusTime.toEpochMilli();
		} catch (Exception e) {
			log.warn("Failed to parse container timestamps for {}", dockerContainer.getId(), e);
			lastStatusUpdate = System.currentTimeMillis();
		}

		PoseidonContainer ret = new PoseidonContainer();
		ret.setDockerId(dockerContainer.getId());
		ret.setImage(dockerContainer.getImage());
		ret.setImageId(dockerContainer.getImageId());
		ret.setLabels(dockerContainer.getLabels());

		ret.setNames(Arrays.stream(dockerContainer.getNames()).map(name -> name.substring(1)).toList()); // docker prefixes container names with /, remove the /
		if(dockerContainer.getPorts().length > 0 && dockerContainer.getPorts()[0].getPublicPort() != null) {
			ret.setExternalPort(dockerContainer.getPorts()[0].getPublicPort());
		}

		ret.setLastStateUpdate(lastStatusUpdate);
		ret.setState(PoseidonContainer.State.fromDockerStatus(currentStatus));

		return ret;
	}


	@PreDestroy
	public void cleanup() {
		try {
			if (eventsCallback != null) {
				eventsCallback.close();
			}
			if (dockerClient != null) {
				dockerClient.close();
			}
		} catch (IOException e) {
			log.error("Error during Docker service cleanup", e);
		}
	}

}
