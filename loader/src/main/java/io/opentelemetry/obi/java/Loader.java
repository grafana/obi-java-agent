package io.opentelemetry.obi.java;

import java.lang.instrument.Instrumentation;

public class Loader {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("Hello world!");


    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    // Just a test method functionality, not used in the Agent
    public static void main(String[] args) {
        premain(null, null);
    }
}
