package org.grafana.beyla.instrumentations;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import javax.net.ssl.SSLSession;

public class SSLSessionInst {
    public static ElementMatcher<? super TypeDescription> type() {
        return ElementMatchers
                .isSubTypeOf(SSLSession.class)
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isInterface()));
    }

    public static AgentBuilder.Transformer transformer() {
        return (builder, type, classLoader, module, protectionDomain) ->
                builder
                        .visit(Advice.to(InvalidateAdvice.class)
                                .on(ElementMatchers
                                        .named("finish")
                                        .or(ElementMatchers.named("invalidate"))));
    }

    public static final class InvalidateAdvice {
        @Advice.OnMethodEnter//(suppress = Throwable.class)
        public static void invalidate(
                @Advice.This final SSLSession session) {
            if (session.getId().length == 0) {
                return;
            }
            SSLStorage.cleanupConnectionForSession(session);
        }
    }

}
