package org.example;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.jaxrs.JerseyDockerHttpClient;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class App {

    private static DockerClient makeClient() {
        DockerClientConfig config = makeConfig();
        DockerHttpClient dockerHttp = new OkDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        return DockerClientBuilder.getInstance(config).withDockerHttpClient(dockerHttp).build();
    }

    private static DockerClientConfig makeConfig() {
        return DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    }

    public static void main(String[] args) throws Exception {

        DockerClient docker = makeClient();

        try {

            System.out.println("Pulling image...");
            docker.pullImageCmd("alpine:latest").exec(new PullReport()).awaitCompletion();

            System.out.println("Creating container...");
            CreateContainerResponse container = docker
                    .createContainerCmd("alpine:latest")
                    .withCmd("/bin/sh", "-c", "while true; do sleep 100; done")
                    .exec();

            String containerId = container.getId();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Clean up");
                try { docker.killContainerCmd(containerId).exec(); } catch (Exception ignored) {}
                try { docker.removeContainerCmd(containerId).exec(); } catch (Exception ignored) {}
            }));

            System.out.println("Starting container...");
            docker.startContainerCmd(containerId).exec();

            System.out.println("Executing echo...");
            ExecCreateCmdResponse execCmd = docker
                    .execCreateCmd(containerId)
                    .withCmd("cat")
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStdin(true)
                    .exec();

            AnyResultCallBack<Frame> cb = docker
                    .execStartCmd(execCmd.getId())
                    .withStdIn(new MyStream())
                    .exec(makeLogResult());

            cb.awaitCompletion();

        } catch (Exception e) {

            e.printStackTrace();

        }


    }

    static class PullReport extends PullImageResultCallback {

        @Override
        public void onNext(PullResponseItem item) {
            super.onNext(item);
            System.out.println("Pull:" + item.getProgressDetail());
        }

    }

    static class MyStream extends InputStream {

        int i = 10;
        byte [] data = null;
        int ptr;

        @Override
        public int read() throws IOException {
            byte [] b = new byte[1];
            int r = read(b, 0, 1);
            if (r < 0) { return r; }
            return b[0];
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {

            if (data == null || ptr == data.length) {
                if (i == 0) { return -1; }
                data = (i + "\n").getBytes(StandardCharsets.UTF_8);
                i--;
                ptr = 0;
            }

            int maxLen = Math.min(len, data.length - ptr);
            System.arraycopy(data, ptr, b, off, maxLen);
            ptr += maxLen;

            return maxLen;
        }

        @Override
        public void close() {

        }
    }

    private static AnyResultCallBack<Frame> makeLogResult() {

        return new AnyResultCallBack<Frame>() {

            protected void handle(Frame item) {
                System.out.println(item.toString());
            }

        };

    }


    static abstract class AnyResultCallBack<T> extends ResultCallback.Adapter<T> {

        @Override
        public final void onNext(T object) {
            try {
                handle(object);
            } catch (Throwable e) {
                System.out.println("Failed to process returned object: "+object);
                onError(e);
            }
        }

        protected abstract void handle(T object);

    }


}
