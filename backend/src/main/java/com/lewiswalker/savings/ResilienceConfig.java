package com.lewiswalker.savings;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Turns on {@code @Retryable}, which is part of Spring Framework 7 rather than a
 * separate library — no Spring Retry, no Resilience4j, nothing to add.
 *
 * <h2>Where retry is applied, and where it is deliberately not</h2>
 *
 * <p>Retry is not a thing you switch on everywhere. Applied carelessly it makes an
 * outage worse in three distinct ways, so each use here is argued for individually.
 *
 * <p><b>It is applied to the two integration ports</b> — the customer directory and
 * account number allocation — and only to their <em>unavailable</em> failures. A remote
 * call that failed because the network hiccuped is worth making again.
 *
 * <p><b>It is not applied to permanent failures.</b> Every policy here lists exactly
 * what it retries. {@code UnknownCustomerException} means the bank has no such record
 * and will still have no such record in two hundred milliseconds: retrying it burns the
 * budget and delays an answer that was already final. This is the single most common way
 * retry is got wrong, and it is why the annotations use {@code includes} rather than
 * retrying everything that is not excluded.
 *
 * <p><b>It is not applied to the database.</b> A connection failure already fails in
 * three seconds thanks to the Hikari timeout; three retries would make the caller wait
 * nine for the same answer while holding a request thread the whole time. The right
 * response to a dependency that is down is to stop calling it — a circuit breaker — not
 * to call it more often. Retrying a struggling dependency adds load exactly when it can
 * least absorb it.
 *
 * <p><b>It is not applied to the sequence-contention loop</b> in {@code AccountService}.
 * That loop is not handling a failure, it is handling a race that another request just
 * won, and the next slot is free <em>now</em>. Exponential backoff would make it slower
 * for no reason whatsoever.
 *
 * <p>Every policy sets jitter. Without it, clients that failed at the same moment retry
 * at the same moment and arrive together.
 *
 * <p>And the honest note: the retry that matters most is the caller's, not ours. A 503
 * from this service carries {@code retryable: true} precisely so the client can decide,
 * with its own budget and its own view of how much the answer is worth. Server-side
 * retry inside a synchronous request can only ever spend the caller's patience for them.
 */
/*
 * proxyTargetClass = true is required here. Without it this configuration has no effect,
 * and no error says so.
 *
 * Every adapter here implements a port interface, so the default JDK proxy implements
 * that interface. The advisor's pointcut inspects the target class, finds @Retryable on
 * the implementation, and creates a proxy - so far so good. But at invocation time the
 * interceptor resolves the retry policy from the method it was handed, which on a JDK
 * proxy is the *interface* method, and the interface carries no annotation. The result
 * is a proxy that adds nothing: one attempt, no retry, no warning, no failure.
 *
 * CGLIB subclasses the concrete class instead, so the annotated method is the one
 * invoked and the policy is found. (It also matches what Spring Boot does elsewhere -
 * spring.aop.proxy-target-class defaults to true.)
 *
 * The alternative is to move @Retryable onto the port interface, which works with JDK
 * proxies. It is not taken here because how hard to try is a property of the transport,
 * not of the question being asked: an in-process adapter and a call to a mainframe
 * deserve different answers, and the port should not presume either.
 *
 * Found by a test that counts attempts; one asserting only that the call eventually
 * succeeded would have passed against a proxy that did nothing.
 */
@Configuration
@EnableResilientMethods(proxyTargetClass = true)
public class ResilienceConfig {
}
