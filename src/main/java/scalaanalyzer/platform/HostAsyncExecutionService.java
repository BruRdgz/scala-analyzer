package scalaanalyzer.platform;

import com.intellij.openapi.application.*;
import org.jetbrains.concurrency.AsyncPromise;

import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs non-blocking read actions synchronously under the host's read lock.
 *
 * Builder calls are accepted and ignored. Submitting runs the read at once on the calling thread, and the
 * continuation given to `finishOnUiThread` follows it there: the host has no UI thread to hand it to, and what the
 * platform finishes on it (a command applied to a preview copy) is what the caller waits for. A failing read
 * propagates from `submit`, not into the returned promise, which nobody observes. Any other terminal operation is
 * unsupported.
 */
public final class HostAsyncExecutionService extends AsyncExecutionService {
  @Override protected ExpirableExecutor createExecutor(Executor executor) { return CallLog.unsupported("AsyncExecutionService.createExecutor"); }
  @Override protected AppUIExecutor createUIExecutor(ModalityState modality) { return CallLog.unsupported("AsyncExecutionService.createUIExecutor"); }
  @Override protected AppUIExecutor createWriteThreadExecutor(ModalityState modality) { return CallLog.unsupported("AsyncExecutionService.createWriteThreadExecutor"); }

  @Override
  // The proxy implements this exact raw interface; the erased result type remains the callable's T.
  @SuppressWarnings("unchecked")
  protected <T> NonBlockingReadAction<T> buildNonBlockingReadAction(Callable<? extends T> callable) {
    CallLog.hit("AsyncExecutionService.buildNonBlockingReadAction");
    AtomicReference<Consumer<Object>> finish = new AtomicReference<>();
    return (NonBlockingReadAction<T>) Proxy.newProxyInstance(
      NonBlockingReadAction.class.getClassLoader(),
      new Class<?>[]{NonBlockingReadAction.class},
      (proxy, method, args) -> {
        String name = method.getName();
        if (name.equals("executeSynchronously")) {
          CallLog.hit("NonBlockingReadAction.executeSynchronously");
          return HostLock.readLocked(callable);
        }
        if (name.equals("submit")) {
          CallLog.hit("NonBlockingReadAction.submit");
          T result = HostLock.readLocked(callable);
          Consumer<Object> continuation = finish.get();
          if (continuation != null) continuation.accept(result);
          AsyncPromise<T> promise = new AsyncPromise<>();
          promise.setResult(result);
          return promise;
        }
        if (name.equals("finishOnUiThread")) {
          CallLog.hit("NonBlockingReadAction.finishOnUiThread");
          // The proxy's `T` is the callable's result type, which is what the continuation consumes.
          @SuppressWarnings("unchecked")
          Consumer<Object> continuation = (Consumer<Object>) args[1];
          finish.set(continuation);
          return proxy;
        }
        if (method.getReturnType() == NonBlockingReadAction.class) {
          CallLog.hit("NonBlockingReadAction." + name + " (builder, ignored)");
          return proxy;
        }
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("equals")) return proxy == args[0];
        if (name.equals("toString")) return "HostNonBlockingReadAction";
        return CallLog.unsupported("NonBlockingReadAction." + name);
      });
  }
}
