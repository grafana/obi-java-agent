package io.opentelemetry.obi.java;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.opentelemetry.obi.java.ebpf.*;
import io.opentelemetry.obi.java.instrumentations.*;
import io.opentelemetry.obi.java.instrumentations.util.NettyChannelExtractor;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassInjector;
import io.opentelemetry.obi.java.instrumentations.util.ByteBufferExtractor;
import net.bytebuddy.utility.JavaModule;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.dynamic.loading.ClassInjector.UsingInstrumentation.Target.BOOTSTRAP;

public class Agent {
    public static int IOCTL_CMD = 0x0b10b1;

    public static boolean debugOn = false;

    public interface CLibrary extends Library {
        CLibrary INSTANCE = Native.load("c", CLibrary.class);

        int ioctl(int fd, int cmd, long argp);
    }

    private static AgentBuilder builder(Map<String, String> opts, Instrumentation inst) {
        AgentBuilder builder = new AgentBuilder.Default()
                .with(new AgentBuilder.LocationStrategy() {
                    @Override
                    public ClassFileLocator classFileLocator(ClassLoader classLoader, JavaModule module) {
                        return ClassFileLocator.ForClassLoader.of(classLoader);
                    }
                })
                .disableClassFormatChanges()
                .ignore(none())
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION) // required for dynamic injection
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE) // required for dynamic injection
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE) // required for dynamic injection
                ;
        if (optEnabled(opts, "debugBB")) {
            builder = builder
                    .with(AgentBuilder.Listener.StreamWriting.toSystemOut());
        }

        return builder;
    }

    private static Map<String, String> parseArgs(String agentArgs) {
        Map<String, String> opts = new HashMap<>();
        if (agentArgs != null && !agentArgs.isEmpty()) {
            String[] options = agentArgs.split(",");
            for (String option : options) {
                String[] keyValue = option.split("=");
                if (keyValue.length == 2) {
                    opts.put(keyValue[0], keyValue[1]);
                }
            }
        }

        return opts;
    }

    private static boolean optEnabled(Map<String, String> opts, String opt) {
        String optVal = opts.getOrDefault(opt, "");
        return optVal.toLowerCase(Locale.getDefault()).equals("true");
    }

    // Main agent load and instrumentation code, this gets invoked directly with -javaagent on the
    // command line
    public static void premain(String agentArgs, Instrumentation inst) {
        Map<String, String> opts = parseArgs(agentArgs);

        if (optEnabled(opts, "debug")) {
            Agent.debugOn = true;
        }

        try {
            initClassesThatNeedToBeBootstrapped();
            injectBootstrapClasses(inst);
            if (Agent.debugOn) {
                setupInstrumentationsDebugging();
            }
        } catch (Exception x) {
            if (Agent.debugOn) {
                x.printStackTrace();
            }
        }

        builder(opts, inst)
                .ignore(none())
                .type(SSLSocketInst.type())
                .transform(SSLSocketInst.transformer())
                .type(SSLEngineInst.type())
                .transform(SSLEngineInst.transformer())
                .type(SocketChannelInst.type())
                .transform(SocketChannelInst.transformer())
                .type(NettySSLHandlerInst.type())
                .transform(NettySSLHandlerInst.transformer())
                .installOn(inst);
    }

    // Needed for Dynamic Agent Injection
    public static void agentmain(String args, Instrumentation inst) throws UnmodifiableClassException {
        premain(args, inst);

        // This reattempt to instrument is required because sometimes. Depending on the classes
        // loaded, some classes disrupt ByteBuddy such that it cannot find the classes we said
        // we want to instrument.
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (SSLSocketInst.matches(clazz)
                || SSLEngineInst.matches(clazz)
                || SocketChannelInst.matches(clazz)
                || NettySSLHandlerInst.matches(clazz)
            ) {
                if (Agent.debugOn) {
                    System.out.println("Retransforming " + clazz);
                }
                try {
                    inst.retransformClasses(clazz);
                } catch (Throwable t) { // Failure can be normal if we've retransformed this class before
                    if (Agent.debugOn) {
                        System.out.println("Error " + t.getMessage());
                    }
                }
            }
        }
    }

    // Just a test method functionality, not used in the Agent
    public static void main(String[] args) {
        premain(null, ByteBuddyAgent.install());
    }

    private static void initClassesThatNeedToBeBootstrapped() throws ClassNotFoundException {
        // Load the serialisation helper classes
        Class.forName(ProxyOutputStream.class.getName());
        Class.forName(ProxyInputStream.class.getName());
        Class.forName(ConnectionInfo.class.getName());
        Class.forName(IOCTLPacket.class.getName());
        Class.forName(OperationType.class.getName());
        Class.forName(Agent.class.getName());
        Class.forName(BytesWithLen.class.getName());
        Class.forName(Connection.class.getName());
        Class.forName(NettyChannelExtractor.class.getName());
        Class.forName(SSLStorage.class.getName());
        Class.forName(ByteBufferExtractor.class.getName());

        // It's hard to predict what classes will this JNA operation use, so we
        // perform one dummy write.
        byte[] data = new byte[]{0};
        Pointer p = new Memory(data.length);
        p.write(0, data, 0, data.length);
        CLibrary.INSTANCE.ioctl(0, IOCTL_CMD, Pointer.nativeValue(p));

        // LRU cache map and some usage to match what we use in the hooks
        Cache<Object, Object> cache = Caffeine.newBuilder()
                .maximumSize(1)
                .build();
        Integer key = 1;
        cache.put(key, new Object());
        cache.getIfPresent(key);
        cache.invalidate(key);
    }

    private static void injectBootstrapClasses(Instrumentation instrumentation) throws IOException {
        File tempDir = Files.createTempDirectory("obi-agent").toFile();
        // Delete on exit in case we throw some sort of exception
        tempDir.deleteOnExit();
        Map<TypeDescription, byte[]> typeMap = new java.util.HashMap<>();
        ClassLoader agentClassLoader = Agent.class.getClassLoader();

        ClassFileLocator locator = new ClassFileLocator.Compound(
                 ClassFileLocator.ForClassLoader.ofSystemLoader(),
                 ClassFileLocator.ForClassLoader.of(agentClassLoader),
                 ClassFileLocator.ForClassLoader.ofPlatformLoader(),
                 ClassFileLocator.ForClassLoader.ofBootLoader()
        );

        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            TypeDescription desc = new TypeDescription.ForLoadedType(clazz);
            if (desc.getName().startsWith("com.sun.")
                    || desc.getName().startsWith("io.opentelemetry.obi.")
                    || desc.getName().startsWith("com.github.benmanes.")
            ) {
                try {
                    byte[] bytes = locator.locate(desc.getName()).resolve();
                    typeMap.put(desc, bytes);
                } catch (Throwable ignored) {
                }
            }
        }

        ClassInjector injector = ClassInjector.UsingInstrumentation.of(tempDir, BOOTSTRAP, instrumentation);
        injector.inject(typeMap);
        tempDir.delete();
    }

    // Must be called after we've called injectBootstrapClasses
    public static void setupInstrumentationsDebugging() {
        try {
            Class<?> sslStorageClass = Class.forName("io.opentelemetry.obi.java.instrumentations.SSLStorage", true, null);
            Field debugOn = sslStorageClass.getDeclaredField("debugOn");
            debugOn.set(null, true);
            System.out.println("Setting up instrumentations debugging");
        } catch (Exception x) {
            x.printStackTrace();
        }
    }
}