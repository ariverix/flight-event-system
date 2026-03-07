package ru.protectinfotrans.eca;

import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;

import java.net.URI;

/**
 * Кастомная стратегия подключения к Docker через TCP.
 * Используется на Windows с Docker Desktop, где named pipe недоступен из Java JVM.
 * Требует включённого TCP-доступа: Docker Desktop Settings → General → Expose daemon on tcp://localhost:2375.
 *
 * Активируется через ~/.testcontainers.properties:
 *   docker.client.strategy=ru.protectinfotrans.eca.TcpDockerClientStrategy
 */
public class TcpDockerClientStrategy extends DockerClientProviderStrategy {

    private static final String TCP_HOST = "tcp://localhost:2375";

    @Override
    public TransportConfig getTransportConfig() {
        return TransportConfig.builder()
                .dockerHost(URI.create(TCP_HOST))
                .build();
    }

    @Override
    public String getDescription() {
        return "TCP Docker Desktop (Windows) — tcp://localhost:2375";
    }

    @Override
    protected boolean isApplicable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 1000;
    }
}
