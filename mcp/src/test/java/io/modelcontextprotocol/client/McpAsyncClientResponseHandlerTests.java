/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.MockMcpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Root;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class McpAsyncClientResponseHandlerTests {

	private InitializeResult initialization(McpAsyncClient asyncMcpClient, MockMcpTransport transport) {
		// Create mock server response
		McpSchema.InitializeResult mockInitResult = new McpSchema.InitializeResult(McpSchema.LATEST_PROTOCOL_VERSION,
				McpSchema.ServerCapabilities.builder()
					.tools(true)
					.resources(true, true) // Enable both resources and resource templates
					.build(),
				new McpSchema.Implementation("test-server", "1.0.0"), "Test instructions");

		// Create a Mono that will handle the initialization and response simulation
		return asyncMcpClient.initialize().doOnSubscribe(subscription -> {
			// Run in a separate reactive context to avoid blocking the main subscription
			Mono.fromRunnable(() -> {
				McpSchema.JSONRPCRequest initRequest = transport.getLastSentMessageAsRequest();
				assertThat(initRequest.method()).isEqualTo(McpSchema.METHOD_INITIALIZE);

				// Send mock server response
				McpSchema.JSONRPCResponse initResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						initRequest.id(), mockInitResult, null);
				transport.simulateIncomingMessage(initResponse);
			}).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe();
		}).block();
	}

	@Test
	void testSuccessfulInitialization() {
		MockMcpTransport transport = new MockMcpTransport();
		McpAsyncClient asyncMcpClient = McpClient.async(transport).build();

		// Verify client is not initialized initially
		assertThat(asyncMcpClient.isInitialized()).isFalse();

		// Create mock server response
		McpSchema.ServerCapabilities mockServerCapabilities = McpSchema.ServerCapabilities.builder()
			.tools(true)
			.resources(true, true) // Enable both resources and resource templates
			.build();
		McpSchema.Implementation mockServerInfo = new McpSchema.Implementation("test-server", "1.0.0");
		McpSchema.InitializeResult mockInitResult = new McpSchema.InitializeResult(McpSchema.LATEST_PROTOCOL_VERSION,
				mockServerCapabilities, mockServerInfo, "Test instructions");

		// Start initialization with reactive handling
		InitializeResult result = asyncMcpClient.initialize().doOnSubscribe(subscription -> {
			// Run in a separate reactive context to avoid blocking the main subscription
			Mono.fromRunnable(() -> {
				McpSchema.JSONRPCRequest initRequest = transport.getLastSentMessageAsRequest();
				assertThat(initRequest.method()).isEqualTo(McpSchema.METHOD_INITIALIZE);

				// Send mock server response
				McpSchema.JSONRPCResponse initResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						initRequest.id(), mockInitResult, null);
				transport.simulateIncomingMessage(initResponse);
				// latch.countDown();
			}).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe();
		}).block();

		// Verify initialized notification was sent
		McpSchema.JSONRPCMessage notificationMessage = transport.getLastSentMessage();
		assertThat(notificationMessage).isInstanceOf(McpSchema.JSONRPCNotification.class);
		McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) notificationMessage;
		assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_INITIALIZED);

		// Verify initialization result
		assertThat(result).isNotNull();
		assertThat(result.protocolVersion()).isEqualTo(McpSchema.LATEST_PROTOCOL_VERSION);
		assertThat(result.capabilities()).isEqualTo(mockServerCapabilities);
		assertThat(result.serverInfo()).isEqualTo(mockServerInfo);
		assertThat(result.instructions()).isEqualTo("Test instructions");

		// Verify client state after initialization
		assertThat(asyncMcpClient.isInitialized()).isTrue();
		assertThat(asyncMcpClient.getServerCapabilities()).isEqualTo(mockServerCapabilities);
		assertThat(asyncMcpClient.getServerInfo()).isEqualTo(mockServerInfo);

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testToolsChangeNotificationHandling() throws JsonProcessingException {
		MockMcpTransport transport = new MockMcpTransport();

		// Create a list to store received tools for verification
		List<McpSchema.Tool> receivedTools = new ArrayList<>();

		// Create a consumer that will be called when tools change
		Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer = tools -> Mono
			.fromRunnable(() -> receivedTools.addAll(tools));

		// Create client with tools change consumer
		McpAsyncClient asyncMcpClient = McpClient.async(transport).toolsChangeConsumer(toolsChangeConsumer).build();

		assertThat(initialization(asyncMcpClient, transport)).isNotNull();

		// Create a mock tools list that the server will return
		Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of(), "required", List.of());
		McpSchema.Tool mockTool = new McpSchema.Tool("test-tool", "Test Tool Description",
				new ObjectMapper().writeValueAsString(inputSchema));
		McpSchema.ListToolsResult mockToolsResult = new McpSchema.ListToolsResult(List.of(mockTool), null);

		// Simulate server sending tools/list_changed notification
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
		transport.simulateIncomingMessage(notification);

		// Simulate server response to tools/list request
		McpSchema.JSONRPCRequest toolsListRequest = transport.getLastSentMessageAsRequest();
		assertThat(toolsListRequest.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);

		McpSchema.JSONRPCResponse toolsListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				toolsListRequest.id(), mockToolsResult, null);
		transport.simulateIncomingMessage(toolsListResponse);

		// Verify the consumer received the expected tools
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(receivedTools).hasSize(1);
			assertThat(receivedTools.get(0).name()).isEqualTo("test-tool");
			assertThat(receivedTools.get(0).description()).isEqualTo("Test Tool Description");
		});

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testRootsListRequestHandling() {
		MockMcpTransport transport = new MockMcpTransport();

		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.roots(new Root("file:///test/path", "test-root"))
			.build();

		assertThat(initialization(asyncMcpClient, transport)).isNotNull();

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_ROOTS_LIST, "test-id", null);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.result())
			.isEqualTo(new McpSchema.ListRootsResult(List.of(new Root("file:///test/path", "test-root"))));
		assertThat(response.error()).isNull();

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testResourcesChangeNotificationHandling() {
		MockMcpTransport transport = new MockMcpTransport();

		// Create a list to store received resources for verification
		List<McpSchema.Resource> receivedResources = new ArrayList<>();

		// Create a consumer that will be called when resources change
		Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer = resources -> Mono
			.fromRunnable(() -> receivedResources.addAll(resources));

		// Create client with resources change consumer
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.resourcesChangeConsumer(resourcesChangeConsumer)
			.build();

		assertThat(initialization(asyncMcpClient, transport)).isNotNull();

		// Create a mock resources list that the server will return
		McpSchema.Resource mockResource = new McpSchema.Resource("test://resource", "Test Resource", "A test resource",
				"text/plain", null);
		McpSchema.ListResourcesResult mockResourcesResult = new McpSchema.ListResourcesResult(List.of(mockResource),
				null);

		// Simulate server sending resources/list_changed notification
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
		transport.simulateIncomingMessage(notification);

		// Simulate server response to resources/list request
		McpSchema.JSONRPCRequest resourcesListRequest = transport.getLastSentMessageAsRequest();
		assertThat(resourcesListRequest.method()).isEqualTo(McpSchema.METHOD_RESOURCES_LIST);

		McpSchema.JSONRPCResponse resourcesListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				resourcesListRequest.id(), mockResourcesResult, null);
		transport.simulateIncomingMessage(resourcesListResponse);

		// Verify the consumer received the expected resources
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(receivedResources).hasSize(1);
			assertThat(receivedResources.get(0).uri()).isEqualTo("test://resource");
			assertThat(receivedResources.get(0).name()).isEqualTo("Test Resource");
			assertThat(receivedResources.get(0).description()).isEqualTo("A test resource");
		});

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testPromptsChangeNotificationHandling() {
		MockMcpTransport transport = new MockMcpTransport();

		// Create a list to store received prompts for verification
		List<McpSchema.Prompt> receivedPrompts = new ArrayList<>();

		// Create a consumer that will be called when prompts change
		Function<List<McpSchema.Prompt>, Mono<Void>> promptsChangeConsumer = prompts -> Mono
			.fromRunnable(() -> receivedPrompts.addAll(prompts));

		// Create client with prompts change consumer
		McpAsyncClient asyncMcpClient = McpClient.async(transport).promptsChangeConsumer(promptsChangeConsumer).build();

		assertThat(initialization(asyncMcpClient, transport)).isNotNull();

		// Create a mock prompts list that the server will return
		McpSchema.Prompt mockPrompt = new McpSchema.Prompt("test-prompt", "Test Prompt Description",
				List.of(new McpSchema.PromptArgument("arg1", "Test argument", true)));
		McpSchema.ListPromptsResult mockPromptsResult = new McpSchema.ListPromptsResult(List.of(mockPrompt), null);

		// Simulate server sending prompts/list_changed notification
		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
		transport.simulateIncomingMessage(notification);

		// Simulate server response to prompts/list request
		McpSchema.JSONRPCRequest promptsListRequest = transport.getLastSentMessageAsRequest();
		assertThat(promptsListRequest.method()).isEqualTo(McpSchema.METHOD_PROMPT_LIST);

		McpSchema.JSONRPCResponse promptsListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
				promptsListRequest.id(), mockPromptsResult, null);
		transport.simulateIncomingMessage(promptsListResponse);

		// Verify the consumer received the expected prompts
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(receivedPrompts).hasSize(1);
			assertThat(receivedPrompts.get(0).name()).isEqualTo("test-prompt");
			assertThat(receivedPrompts.get(0).description()).isEqualTo("Test Prompt Description");
			assertThat(receivedPrompts.get(0).arguments()).hasSize(1);
			assertThat(receivedPrompts.get(0).arguments().get(0).name()).isEqualTo("arg1");
		});

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testSamplingCreateMessageRequestHandling() {
		MockMcpTransport transport = new MockMcpTransport();

		// Create a test sampling handler that echoes back the input
		Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = request -> {
			var content = request.messages().get(0).content();
			return Mono.just(new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT, content, "test-model",
					McpSchema.CreateMessageResult.StopReason.END_TURN));
		};

		// Create client with sampling capability and handler
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		assertThat(initialization(asyncMcpClient, transport)).isNotNull();

		// Create a mock create message request
		var messageRequest = new McpSchema.CreateMessageRequest(
				List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message"))),
				null, // modelPreferences
				"Test system prompt", McpSchema.CreateMessageRequest.ContextInclusionStrategy.NONE, 0.7, // temperature
				100, // maxTokens
				null, // stopSequences
				null // metadata
		);

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, "test-id", messageRequest);
		transport.simulateIncomingMessage(request);

		// Verify response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.error()).isNull();

		McpSchema.CreateMessageResult result = transport.unmarshalFrom(response.result(),
				new TypeReference<McpSchema.CreateMessageResult>() {
				});
		assertThat(result).isNotNull();
		assertThat(result.role()).isEqualTo(McpSchema.Role.ASSISTANT);
		assertThat(result.content()).isNotNull();
		assertThat(result.model()).isEqualTo("test-model");
		assertThat(result.stopReason()).isEqualTo(McpSchema.CreateMessageResult.StopReason.END_TURN);

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testSamplingCreateMessageRequestHandlingWithoutCapability() {
		MockMcpTransport transport = new MockMcpTransport();

		// Create client without sampling capability
		McpAsyncClient asyncMcpClient = McpClient.async(transport)
			.capabilities(ClientCapabilities.builder().build()) // No sampling capability
			.build();

		assertThat(initialization(asyncMcpClient, transport)).isNotNull();

		// Create a mock create message request
		var messageRequest = new McpSchema.CreateMessageRequest(
				List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Test message"))),
				null, null, null, null, 0, null, null);

		// Simulate incoming request
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, "test-id", messageRequest);
		transport.simulateIncomingMessage(request);

		// Verify error response
		McpSchema.JSONRPCMessage sentMessage = transport.getLastSentMessage();
		assertThat(sentMessage).isInstanceOf(McpSchema.JSONRPCResponse.class);

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) sentMessage;
		assertThat(response.id()).isEqualTo("test-id");
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().message()).contains("Method not found: sampling/createMessage");

		asyncMcpClient.closeGracefully();
	}

	@Test
	void testSamplingCreateMessageRequestHandlingWithNullHandler() {
		MockMcpTransport transport = new MockMcpTransport();

		// Create client with sampling capability but null handler
		assertThatThrownBy(
				() -> McpClient.async(transport).capabilities(ClientCapabilities.builder().sampling().build()).build())
			.isInstanceOf(McpError.class)
			.hasMessage("Sampling handler must not be null when client capabilities include sampling");
	}

}
