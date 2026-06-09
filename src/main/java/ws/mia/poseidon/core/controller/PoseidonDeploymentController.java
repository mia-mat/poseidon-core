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
import ws.mia.poseidon.core.PoseidonDeploymentService;
import ws.mia.poseidon.core.docker.DockerService;
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
	private final PoseidonDeploymentService poseidonDeploymentService;

	public PoseidonDeploymentController(EnvironmentService environmentService, PoseidonDeploymentService poseidonDeploymentService) {
		this.environmentService = environmentService;
		this.poseidonDeploymentService = poseidonDeploymentService;
	}

	// for GitHub to POST Docker image updates to
	@PostMapping("/deploy")
	public ResponseEntity<Object> deploy(@RequestHeader("X-Hub-Signature-256") String signature,
										 HttpServletRequest request) {
		try {
			byte[] body = request.getInputStream().readAllBytes();

			// verify that the deployment is signed with poseidon's secret
			if (!verifySignature(body, signature)) {
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
			}

			// we expect a JSON object, corresponding 1:1 to our DTO
			ObjectMapper mapper = new ObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true);

			PoseidonDeploymentPayload payload = mapper.readValue(body, PoseidonDeploymentPayload.class);

			if(!validateDeploymentPayload(payload)) {
				return ResponseEntity.badRequest().body(payload);
			}

			poseidonDeploymentService.deploy(payload);
			return ResponseEntity.ok("Successfully Deployed :)");

		} catch (PhoenixClientException | PhoenixServerException e) {
			log.warn("Deployed with Phoenix error", e);
			return ResponseEntity.ok("Deployed with Phoenix Error");
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

	private boolean validateDeploymentPayload(PoseidonDeploymentPayload payload) {
		if (payload.getImage() == null || payload.getImage().isBlank()) {
			return false;
		}

		if (payload.getRepository().isBlank()
		||  payload.getRef().isBlank()
		||  payload.getBranch().isBlank()
		||  payload.getRepositoryId().isBlank()
		||  payload.getRepositoryName().isBlank()
		||  payload.getRepositoryUrl().isBlank()) return false;

		return true;
	}

}
