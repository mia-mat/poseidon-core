package ws.mia.poseidon.core.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DockerClientConfiguration {

	@Bean
	public DockerClient dockerClient() {
		DockerClientConfig dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();

		DockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder()
				.dockerHost(dockerConfig.getDockerHost())
				.sslConfig(dockerConfig.getSSLConfig())
				.connectionTimeout(Duration.ofSeconds(30))
				.responseTimeout(Duration.ofSeconds(45))
				.build();

		return DockerClientBuilder.getInstance(dockerConfig)
				.withDockerHttpClient(dockerHttpClient)
				.build();
	}


}
