package io.opentelemetry.obi.java;

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;

public class Loader {
    // Load JNA in the system class loader, otherwise it won't load the shared libs.
    public interface CLibrary extends Library {
        CLibrary INSTANCE = Native.load("c", CLibrary.class);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        String agentResourcePath = "agent/agent.jar";

        File tempAgentJar;
        try (InputStream agentJarStream = Loader.class.getClassLoader().getResourceAsStream(agentResourcePath)) {
            if (agentJarStream == null) {
                throw new FileNotFoundException("Resource not found: " + agentResourcePath);
            }

            tempAgentJar = Files.createTempFile("agent", ".jar").toFile();
            tempAgentJar.deleteOnExit();
            try (OutputStream out = Files.newOutputStream(tempAgentJar.toPath())) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = agentJarStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            URL agentJarUrl = tempAgentJar.toURI().toURL();
            try (URLClassLoader agentClassLoader = new URLClassLoader(new URL[]{agentJarUrl}, null)) {
                Class<?> mainClass = agentClassLoader.loadClass("io.opentelemetry.obi.java.Agent");

                java.lang.reflect.Method mainMethod = mainClass.getMethod("premain", String.class, Instrumentation.class);
                mainMethod.invoke(null, agentArgs, inst);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    // Just a test method functionality, not used in the Agent
    public static void main(String[] args) {
        premain(null, null);
    }
}
