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
		return new PhoenixHttpClient(environmentService.getPhoenixUrl(), environmentService.getPhoenixAuthToken());
	}

}
