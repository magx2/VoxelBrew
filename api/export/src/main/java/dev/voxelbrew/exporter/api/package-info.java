/**
 * Public, frontend-neutral contracts for implementing and discovering VoxelBrew exporters.
 *
 * <p>Start with an {@link dev.voxelbrew.exporter.api.ExporterProvider}: expose stable metadata and
 * create an {@link dev.voxelbrew.exporter.api.Exporter} using the supplied worker executor. Register
 * the provider's class name in
 * {@code META-INF/services/dev.voxelbrew.exporter.api.ExporterProvider} in an independently built
 * plugin JAR. The host supplies API classes at runtime; do not package copies into that JAR.
 *
 * <p>An export receives a document and an {@link dev.voxelbrew.exporter.api.ExportWindow}. Request
 * settings synchronously, then perform conversion and publication on the worker. For example:
 *
 * <pre>{@code
 * var request = new FileExportDialog("Export report", "txt");
 * if (parent.show(request).isEmpty()) {
 *     return CompletableFuture.failedFuture(new CancellationException());
 * }
 * var destination = request.getSettings();
 * return CompletableFuture.supplyAsync(() -> publish(document, destination), worker);
 * }</pre>
 *
 * <p>Here {@code publish} is an implementation-owned operation that validates the output, honors
 * overwrite consent and returns only after safe publication. Exceptions must reach the returned
 * future. A cloud exporter can instead define an {@link dev.voxelbrew.exporter.api.ExportDialog}
 * for account/project settings and return an upload receipt. Every frontend using that request
 * needs a corresponding handler; provider discovery does not generate user interfaces.
 *
 * <p>The API exposes shared document values, not native geometry handles or an evaluation service.
 * Exporters requiring evaluated solids or meshes must obtain those through their implementation
 * dependencies. Keep frontend types and native pointers out of custom public interaction contracts.
 * The module README contains a complete provider, packaging instructions and validation guidance.
 */
package dev.voxelbrew.exporter.api;
