package com.github.henningcash.test;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Demo showing that closing exec instances streaming stdout will leak threads
 * if there is no output and close() is called
 */
public class Application {
    private static final String DOCKER_IMAGE = "alpine:3";
    private static final DockerClient dockerClient = setupDocker();

    public static void main(String[] args) {
        pullDockerImage();

        var containerId = runContainer("tail", "-f", "/dev/null");
        System.out.printf("Container %s is running%n", containerId);

        System.out.println("Running 10 exec instances. Press Ctrl+C to cancel");
        try {
            for (int i = 1; i <= 10; i++) {
                System.out.printf("Exec No %d...%n", i);

                // tailing /dev/null does NOT produce output and will leak threads
                var streamingAdapter = runExec(containerId, "tail", "-f", "/dev/null");
                // watch produces output every 1sec and does NOT leak threads
                //var streamingAdapter = runExec(containerId, "watch", "-n", "1", "date");

                TimeUnit.SECONDS.sleep(3);
                System.out.println("Closing exec instance prematurely...");
                try {
                    streamingAdapter.close();
                    System.out.println("Called close()");
                } catch (IOException e) {
                    System.err.println("Error during close: " + e.getMessage());
                }
                // Wait for streaming thread to finish
                TimeUnit.SECONDS.sleep(2);

                printStreamThreads();
                System.out.println("----------------------------------");
            }
        } catch (InterruptedException e) {

        } finally {
            dockerClient.stopContainerCmd(containerId).exec();
            dockerClient.removeContainerCmd(containerId).exec();
        }
    }

    static final DockerClient setupDocker() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        return DockerClientBuilder.getInstance(config).withDockerHttpClient(
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(URI.create("unix:///var/run/docker.sock"))
                        .build()
        ).build();
    }

    static Closeable runExec(String containerId, String... cmd) {
        var execResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withTty(true)
                .withAttachStdout(true)
                .withAttachStdin(true)
                .exec();
        var streamingAdapter = new ResultCallback.Adapter<Frame>();
        dockerClient.execStartCmd(execResponse.getId())
                .withTty(true)
                .withDetach(false)
                .withStdIn(System.in)
                .exec(streamingAdapter);

        return streamingAdapter;
    }

    static String runContainer(String... cmd) {
        var createContainerResponse = dockerClient.createContainerCmd(DOCKER_IMAGE)
                .withCmd(cmd)
                .exec();
        var containerId = createContainerResponse.getId();
        dockerClient.startContainerCmd(containerId).exec();
        return containerId;
    }

    static void pullDockerImage() {
        try {
            System.out.printf("Pulling %s%n", DOCKER_IMAGE);
            dockerClient.pullImageCmd(DOCKER_IMAGE).start().awaitCompletion();
            System.out.println("Pull complete!");
        } catch (InterruptedException e) {
            System.err.print("Pull was canceled");
            System.exit(1);
        } catch (DockerClientException e) {
            System.err.print("Pull failed");
            System.exit(1);
        }
    }

    static void printStreamThreads() {
        var streamThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("docker-java-stream"))
                .collect(Collectors.toList());
        System.out.printf("There are %d streaming threads:%n", streamThreads.size());
        streamThreads.forEach(thread -> System.out.println(thread.getName()));
    }
}
