/*
 * Copyright 2024-2024 the original author or authors.
 */

package org.modelcontextprotocol.server;

import org.junit.jupiter.api.Timeout;
import org.modelcontextprotocol.server.transport.StdioServerTransport;
import org.modelcontextprotocol.spec.ServerMcpTransport;

/**
 * Tests for {@link McpSyncServer} using {@link StdioServerTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpSyncServerTests extends AbstractMcpSyncServerTests {

	@Override
	protected ServerMcpTransport createMcpTransport() {
		return new StdioServerTransport();
	}

}
