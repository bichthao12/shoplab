package com.shoplab;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** public để test ở mọi package (product, order...) đều import được. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Cùng phiên bản với docker-compose.yml, để test chạy trên đúng bản PostgreSQL sẽ dùng thật
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
	}

}
