package ws.mia.poseidon.core.env;

import org.springframework.stereotype.Service;

@Service
public class DefaultEnvironmentService implements EnvironmentService{


	@Override
	public String getPhoenixUrl() {
		String ret = System.getenv("PHOENIX_URL");
		if(ret == null) throw new IllegalStateException("PHOENIX_URL environment variable not set!");
		return ret;
	}

	@Override
	public String getPhoenixAuthToken() {
		return System.getenv("PHOENIX_URI"); // we can return this even if null since auth may be null
	}

	@Override
	public String getDeploymentSecret() {
		String ret = System.getenv("DEPLOY_WEBHOOK_SECRET");
		if(ret == null) throw new IllegalStateException("DEPLOY_WEBHOOK_SECRET environment variable not set!");
		return ret;
	}

	@Override
	public String getGhcrUsername() {
		String ret = System.getenv("GHCR_USERNAME");
		if(ret == null) throw new IllegalStateException("GHCR_USERNAME environment variable not set!");
		return ret;
	}

	@Override
	public String getGhcrToken() {
		String ret = System.getenv("GHCR_TOKEN");
		if(ret == null) throw new IllegalStateException("GHCR_TOKEN environment variable not set!");
		return ret;
	}

	@Override
	public String getSecretDirectory() {
		String ret = System.getenv("SECRETS_DIR");
		if(ret == null) ret = "/secrets";
		return ret;
	}

	@Override
	public int getPhoenixPort() {
		return 8080;
	}
}
