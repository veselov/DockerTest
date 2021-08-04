package org.example;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.jaxrs.JerseyDockerHttpClient;
import com.github.dockerjava.netty.NettyDockerCmdExecFactory;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

public class App {

    private static DockerClient makeClient() {
        DockerClientConfig config = makeConfig();
        DockerHttpClient dockerHttp = new JerseyDockerHttpClient.Builder().dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        return DockerClientBuilder.getInstance(config).withDockerHttpClient(dockerHttp).withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
    }

    private static DockerClientConfig makeConfig() {
        return DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    }

    public static void main(String[] args) throws Exception {

        DockerClient docker = makeClient();

        try {

            System.out.println("Pulling image...");
            docker.pullImageCmd("alpine:latest").exec(new PullReport()).awaitCompletion();

            File tempDir = File.createTempFile("javadocker", "dockerfile");
            Files.delete(tempDir.toPath());
            Files.createDirectories(tempDir.toPath());

            File temp = File.createTempFile("javadocker", "dockerfile", tempDir);

            try (FileWriter fw = new FileWriter(temp)) {
                fw.write("FROM alpine:latest\n");
                fw.write("RUN apk add strace\n");
            }

            System.out.println("Building image...");

            BuildImageResultCallback bcr =
                    docker.buildImageCmd()
                            .withDockerfile(temp.getAbsoluteFile())
                            .withBaseDirectory(tempDir)
                            .exec(new BuildImageResultCallback() {
                                @Override
                                public void onNext(BuildResponseItem item) {
                                    super.onNext(item);
                                }
                            });

            String imageId = bcr.awaitImageId();

            System.out.println("Creating container...");
            CreateContainerResponse container = docker
                    .createContainerCmd(imageId)
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
                    // .withCmd("/bin/sh", "-c", "while true; do read -r line || break; sleep 1; echo $line; done")
                    // .withCmd("cat")
                    .withCmd("/usr/bin/strace", "-o", "/tmp/1.trs", "wc")
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStdin(true)
                    .exec();

            // !!!!! Pick your inFile to see the problem
            // cuts off at 98,304, should be 3,188,018
            InputStream inFile = new GzipCompressorInputStream(Import.class.getResourceAsStream("/wtf.txt.gz"));
            // doesn't cut off
            // InputStream inFile = new MyStream();
            // cuts off at random values around 450,000, should be 1,000,000 lines
            // InputStream inFile = fullStream();
            // works because the buffer is forcefully metered
            // InputStream inFile = new MeteredBufferedStream(new GzipCompressorInputStream(Import.class.getResourceAsStream("/wtf.txt.gz")), 14);

            AnyResultCallBack<Frame> cb = docker
                    .execStartCmd(execCmd.getId())
                    // .withStdIn(new MyStream())
                    .withStdIn(inFile)
                    .exec(makeLogResult());

            cb.awaitCompletion();

            System.out.println("Exec completed");

            try (InputStream is = docker.copyArchiveFromContainerCmd(containerId, "/tmp/1.trs").exec();
                 FileOutputStream fos = new FileOutputStream("1.trs.tar")) {

                // $TODO: untar the file

                byte [] data = new byte[16384];
                while (true) {
                    int nr = is.read(data);
                    if (nr < 0) { break; }
                    fos.write(data, 0, nr);
                }

            }

            System.out.println("Copied?");

        } catch (Exception e) {

            e.printStackTrace();

        }


    }

    static InputStream fullStream() throws IOException {

        MyStream ms = new MyStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int r = ms.read();
            if (r < 0) { break; }
            out.write(r);
        }

        return new ByteArrayInputStream(out.toByteArray());

    }

    static class PullReport extends PullImageResultCallback {

        @Override
        public void onNext(PullResponseItem item) {
            super.onNext(item);
            System.out.println("Pull:" + item.getProgressDetail());
        }

    }

    static class MyStream extends InputStream {

        int i = 1000000;
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
                if (i == 0) {
                    System.out.println("<< STREAM EXHAUSTED");
                    return -1;
                }
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
            System.out.println("<< STREAM CLOSED");
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

    private static class MeteredBufferedStream extends FilterInputStream {

        private final int bufSize;

        public MeteredBufferedStream(InputStream src, int bufSize) {
            super(src);
            this.bufSize = bufSize;
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {

            len = Math.min(len, bufSize);
            return super.read(b, off, bufSize);

        }
    }
}
