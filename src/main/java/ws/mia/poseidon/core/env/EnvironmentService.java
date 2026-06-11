package ws.mia.poseidon.core.env;

import jakarta.annotation.Nullable;
import org.springframework.stereotype.Service;

@Service
public interface EnvironmentService {

	@Nullable
	String getPoseidonAPIToken();

	@Nullable
	String getPhoenixUrl();

	@Nullable
	String getPhoenixAuthToken();

	String getDeploymentSecret();

	String getGhcrUsername();

	String getGhcrToken();

	String getSecretDirectory();

	int getPhoenixPort();



}
