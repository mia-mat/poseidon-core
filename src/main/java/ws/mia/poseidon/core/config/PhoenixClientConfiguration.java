package ws.mia.poseidon.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ws.mia.phoenix.api.PhoenixClient;
import ws.mia.phoenix.api.PhoenixHttpClient;
import ws.mia.poseidon.core.env.EnvironmentService;

@Configuration
public class PhoenixClientConfiguration {

	@Bean
	public PhoenixClient phoenixClient(EnvironmentService environmentService) {
		String url = environmentService.getPhoenixUrl();
		if (url == null) return null;
		return new PhoenixHttpClient(url, environmentService.getPhoenixAuthToken());
	}

}
