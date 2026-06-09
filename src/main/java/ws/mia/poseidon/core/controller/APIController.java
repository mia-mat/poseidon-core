package ws.mia.poseidon.core.controller;

import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ws.mia.poseidon.api.model.PoseidonContainer;
import ws.mia.poseidon.core.ServerSideEventService;
import ws.mia.poseidon.core.docker.DockerEventListenerService;

import java.util.List;

@RestController
@RequestMapping("/api")
public class APIController {

	private final BuildProperties buildProperties;
	private final ServerSideEventService serverSideEventService;
	private final DockerEventListenerService dockerEventListenerService;

	public APIController(BuildProperties buildProperties, ServerSideEventService serverSideEventService, DockerEventListenerService dockerEventListenerService) {
		this.buildProperties = buildProperties;
		this.serverSideEventService = serverSideEventService;
		this.dockerEventListenerService = dockerEventListenerService;
	}

	@GetMapping(value = "version", produces = "text/plain")
	public String getVersion() {
		return buildProperties.getVersion();
	}

	@GetMapping("containers")
	public List<PoseidonContainer> containers() {
		return dockerEventListenerService.getAllContainers();
	}

	@GetMapping("containers/event-stream")
	public SseEmitter sseConnect() {
		return serverSideEventService.registerClient();
	}
}
