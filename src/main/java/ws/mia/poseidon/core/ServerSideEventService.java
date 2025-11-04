package ws.mia.poseidon.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ws.mia.poseidon.api.model.PoseidonContainerEvent;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ServerSideEventService {

	private static final Logger log = LoggerFactory.getLogger(ServerSideEventService.class);

	private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

	public SseEmitter registerClient() {
		String id = UUID.randomUUID().toString();
		SseEmitter emitter = new SseEmitter(0L);
		emitters.put(id, emitter);

		emitter.onCompletion(() -> emitters.remove(id));
		emitter.onTimeout(() -> emitters.remove(id));
		emitter.onError(e -> emitters.remove(id));

		return emitter;
	}

	protected void removeClient(String clientId) {
		SseEmitter emitter = emitters.remove(clientId);
		if (emitter != null) {
			try {
				emitter.complete();
			} catch (Exception ignored) {}
		}
	}

	public void sendEvent(PoseidonContainerEvent event) {
		log.debug("Sending SSE event: {} for container {}", event.getEventName(), event.getContainer().getDockerId());

		emitters.forEach((clientId, emitter) -> {
			try {
				emitter.send(SseEmitter.event()
						.name(event.getEventName())
						.data(event));
			} catch (IOException e) {
				removeClient(clientId);
			}
		});
	}

}
