package spring.ai.mcp.client.stdio;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import spring.ai.mcp.spec.DefaultMcpTransport;
import spring.ai.mcp.spec.McpSchema.JSONRPCMessage;
import spring.ai.mcp.spec.McpSchema.JSONRPCNotification;
import spring.ai.mcp.spec.McpSchema.JSONRPCRequest;
import spring.ai.mcp.spec.McpSchema.JSONRPCResponse;

import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.util.Assert;

/**
 * Stdio client for communicating with a server process.
 */
public class StdioServerTransport extends DefaultMcpTransport {

	private Process process;

	private BufferedReader processErrorReader;

	private BufferedReader processReader;

	private BufferedWriter processWriter;

	private Scheduler inboundScheduler;
	private Scheduler outboundScheduler;
	private Scheduler errorScheduler;

	private volatile boolean isRunning;

	private final StdioServerParameters params;

	public StdioServerTransport(StdioServerParameters params) {
		this(params, Duration.ofMillis(100));
	}

	public StdioServerTransport(StdioServerParameters params, Duration writeTimeout) {
		super(writeTimeout);

		Assert.notNull(params, "Server parameters must not be null");

		this.params = params;

		// Start threads
		this.inboundScheduler =
				Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
						"inbound");
		this.outboundScheduler =
				Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
						"outbound");
		this.errorScheduler =
				Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
						"error");
	}

	@Override
	public void start() {
		// Prepare command and environment
		List<String> fullCommand = new ArrayList<>();
		fullCommand.add(params.getCommand());
		fullCommand.addAll(params.getArgs());

		ProcessBuilder processBuilder = new ProcessBuilder(fullCommand);
		processBuilder.environment().putAll(params.getEnv());

		// Start the process
		try {
			this.process = processBuilder.start();
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to start process with command: " + fullCommand, e);
		}

		// Validate process streams
		if (this.process.getInputStream() == null || process.getOutputStream() == null) {
			this.process.destroy();
			throw new RuntimeException("Process input or output stream is null");
		}

		// Initialize readers and writers
		this.processErrorReader = this.process.errorReader();
		this.processReader = this.process.inputReader();
		this.processWriter = this.process.outputWriter();

		// Start threads
		this.isRunning = true;
		startInboundProcessing();
		startOutboundProcessing();
		startErrorProcessing();

	}

	public void awaitForExit() {
		try {
			this.process.waitFor();
		}
		catch (InterruptedException e) {
			throw new RuntimeException("Process interrupted", e);
		}
	}

	private void startErrorProcessing() {
		this.errorScheduler.schedule(() -> {
			try {
				String line;
				while (isRunning && processErrorReader != null && (line = processErrorReader.readLine()) != null) {
					try {
						System.out.println("Received error line: " + line);
						// TODO: handle errors, etc.
						this.getErrorSink().tryEmitNext(line);
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			}
			catch (IOException e) {
				if (this.isRunning) {
					throw new RuntimeException(e);
				}
			}
			finally {
				this.isRunning = false;
			}
		});
	}

	private JSONRPCMessage deserializeJsonRpcMessage(String jsonText) throws IOException {

		var map = ModelOptionsUtils.jsonToMap(jsonText);

		// Determine message type based on specific JSON structure
		if (map.containsKey("method") && map.containsKey("id")) {
			return ModelOptionsUtils.OBJECT_MAPPER.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return ModelOptionsUtils.OBJECT_MAPPER.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return ModelOptionsUtils.OBJECT_MAPPER.convertValue(map, JSONRPCResponse.class);
		}

		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	private void startInboundProcessing() {
		this.inboundScheduler.schedule(() -> {
			try {
				String line;
				while (this.isRunning && this.processReader != null && (line = this.processReader.readLine()) != null) {
					try {
						JSONRPCMessage message = deserializeJsonRpcMessage(line);
						if (!this.getInboundSink().tryEmitNext(message).isSuccess()) {
							// TODO: Back off, reschedule, give up?
							throw new RuntimeException("Failed to enqueue message");
						}
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			}
			catch (IOException e) {
				if (isRunning) {
					throw new RuntimeException(e);
				}
			}
			finally {
				isRunning = false;
			}
		});
	}

	private void startOutboundProcessing() {
		this.getOutboundSink()
		    .asFlux()
		    // this bit is important since writes come from user threads and we
		    // want to ensure that the actual writing happens on a dedicated thread
		    .publishOn(outboundScheduler)
		    .handle((message, s) -> {
			    if (message != null) {
				    try {
					    this.processWriter.write(ModelOptionsUtils.toJsonString(message));
					    this.processWriter.newLine();
					    this.processWriter.flush();
					    s.next(message);
				    }
				    catch (IOException e) {
					    s.error(new RuntimeException(e));
				    }
			    }
		    })
		    .subscribe();
	}

	// TODO: provide a non-blocking variant with graceful option
	public void stop() {
		this.outboundScheduler.dispose();
		this.inboundScheduler.dispose();
		this.errorScheduler.dispose();

		// Destroy process
		if (this.process != null) {
			this.process.destroyForcibly();
		}
	}

	@Override
	public void close() {
		stop();
		
		super.close(); // Do we need this?

		// Close resources
		if (this.processReader != null) {
			try {
				this.processReader.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (this.processWriter != null) {
			try {
				this.processWriter.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}