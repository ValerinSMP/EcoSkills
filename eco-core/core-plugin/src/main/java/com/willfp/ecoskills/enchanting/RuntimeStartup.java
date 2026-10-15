package com.willfp.ecoskills.enchanting;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Internal, MAIN-only native lifecycle gate. No timer, polling, retries or public purchase API. */
final class RuntimeStartup implements AutoCloseable {
    private final Consumer<Runnable> afterLoad, afterReload;
    private final BooleanSupplier enabled;
    private final Runnable initialize;
    private final Consumer<Throwable> failure;
    private boolean armed, afterLoadSeen, attempted, closed;

    RuntimeStartup(Consumer<Runnable> afterLoad, Consumer<Runnable> afterReload,
                   BooleanSupplier enabled, Runnable initialize, Consumer<Throwable> failure) {
        this.afterLoad=afterLoad; this.afterReload=afterReload; this.enabled=enabled;
        this.initialize=initialize; this.failure=failure;
    }
    void arm() {
        if (armed || closed) return;
        armed=true;
        try {
            // EcoPlugin invokes afterLoad callbacks BEFORE its final startup reload.
            afterLoad.accept(() -> { if (!closed) afterLoadSeen=true; });
            // END follows category loading (START) and handleReload, even when reload cancels tasks.
            afterReload.accept(this::run);
        } catch (Throwable problem) { attempted=true; failure.accept(problem); }
    }
    private void run() {
        if (closed || attempted || !afterLoadSeen) return;
        attempted=true;
        try { if (enabled.getAsBoolean()) initialize.run(); }
        catch (Throwable problem) { failure.accept(problem); }
    }
    @Override public void close() { closed=true; }
}
