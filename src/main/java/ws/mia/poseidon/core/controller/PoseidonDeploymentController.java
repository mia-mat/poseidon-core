package ws.mia.poseidon.core.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import ws.mia.phoenix.api.PhoenixClient;
import ws.mia.phoenix.api.exception.PhoenixClientException;
import ws.mia.phoenix.api.exception.PhoenixServerException;
import ws.mia.phoenix.api.model.Route;
import ws.mia.poseidon.api.model.PoseidonDeploymentPayload;
import ws.mia.poseidon.core.docker.DockerPushService;
import ws.mia.poseidon.core.env.EnvironmentService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Main CI/CD controller
 */
@Controller
public class PoseidonDeploymentController {


	private static final Logger log = LoggerFactory.getLogger(PoseidonDeploymentController.class);
	private final EnvironmentService environmentService;
	private final PhoenixClient phoenixClient;
	private final DockerPushService dockerPushService;

	public PoseidonDeploymentController(EnvironmentService environmentService, PhoenixClient phoenixClient, DockerPushService dockerPushService) {
		this.environmentService = environmentService;
		this.phoenixClient = phoenixClient;
		this.dockerPushService = dockerPushService;
	}

	// for GitHub to POST Docker image updates to
	@PostMapping("/deploy")
	public ResponseEntity<String> update(@RequestHeader("X-Hub-Signature-256") String signature,
										 HttpServletRequest request) {
		try {
			byte[] body = request.getInputStream().readAllBytes();

			// verify that our request is coming from a trusted source
			if (!verifySignature(body, signature)) {
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
			}

			// we expect a JSON object, corresponding 1:1 to our DTO
			ObjectMapper mapper = new ObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true);
			PoseidonDeploymentPayload payload = mapper.readValue(body, PoseidonDeploymentPayload.class);

			if (payload.getImage() == null || payload.getImage().isBlank()) {
				return ResponseEntity.badRequest().body("Missing 'image' field in payload");
			}

			if (payload.getRepository() == null || payload.getRepository().getName() == null || payload.getRepository().getName().isBlank()) {
				return ResponseEntity.badRequest().body("Invalid repository name");
			}

			final Map<String, String> labels = dockerPushService.extractDockerfileLabels(payload.getImage());
			Optional<Integer> dockerInternalPort = dockerPushService.getInternalPortLabel(labels);
			Optional<String> phoenixSource = dockerPushService.getPhoenixSourceLabel(labels);
			boolean phoenixSelf = dockerPushService.getPhoenixSelfLabel(labels);


			// if we don't have an internal port, we don't have any need for an external port
			Optional<Integer> dockerExternalPort = Optional.ofNullable(dockerInternalPort.isPresent() ? generateExternalPort(phoenixSelf) : null);

			dockerPushService.deployGHCRImage(payload, dockerInternalPort, dockerExternalPort);

			// we only update phoenix routes if this routes externally, else we can delete the record since we're not routing
			if (phoenixSource.isPresent() && dockerExternalPort.isPresent()) {
				Route newRoute = new Route.Builder()
						.source(phoenixSource.get())
						.aliases(dockerPushService.getPhoenixAliasesLabel(labels))
						.destination(dockerPushService.getDockerHost(true) + ":" + generateExternalPort(phoenixSelf))
						.build();

				// we should really set a custom _repo field or something, but this works decently.
				if (phoenixClient.routeExists(phoenixSource.get())) {
					// modify route
					phoenixClient.modifyRoute(phoenixSource.get(),
							new Route.Builder().from(phoenixClient.getRoute(phoenixSource.get()).orElseThrow())
									.aliases(newRoute.getAliases())
									.destination(newRoute.getDestination()).build()
					);

					log.info("Modified phoenix route {}", phoenixSource.get());
				} else {
					// create new route
					phoenixClient.pushRoute(newRoute);
					log.info("Created phoenix route {}", phoenixSource.get());
				}

			} else {
				phoenixClient.removeRoute(phoenixSource.orElseThrow());
				log.info("Removed phoenix route {}", phoenixSource.get());
			}

			return ResponseEntity.noContent().build();
		} catch (PhoenixClientException | PhoenixServerException e) {
			log.warn("Successfully deployed with phoenix error", e);
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			log.warn("Failed to deploy from /deploy endpoint", e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Deployment failed: " + e.getMessage());
		}
	}

	private boolean verifySignature(byte[] payload, String sigHeader) throws Exception {
		String prefix = "sha256=";
		if (sigHeader == null || !sigHeader.startsWith(prefix)) return false;
		String sentSig = sigHeader.substring(prefix.length());

		Mac hmac = Mac.getInstance("HmacSHA256");
		hmac.init(new SecretKeySpec(environmentService.getDeploymentSecret().getBytes(), "HmacSHA256"));
		byte[] digest = hmac.doFinal(payload);
		String actualSig = bytesToHex(digest);
		return MessageDigest.isEqual(actualSig.getBytes(), sentSig.getBytes());

	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	private int generateExternalPort(boolean phoenixSelf) {
		if (phoenixSelf)
			return environmentService.getPhoenixPort(); // we keep a certain port reserved for phoenix so we dont need to edit our reverse proxy (caddy) config

		// get a safe port
		int port = ThreadLocalRandom.current().nextInt(20000, 40000);
		if (phoenixClient.getRoutes().stream().anyMatch(route -> route.getDestination().equals(dockerPushService.getDockerHost(true) + ":" + port))) {
			// conflict
			return generateExternalPort(false);
		}

		return port;
	}

}
