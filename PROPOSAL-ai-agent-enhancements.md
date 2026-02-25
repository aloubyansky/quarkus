# Proposal: Enhancing Quarkus Dev Mode for AI Coding Agents

**Author:** [Your Name]
**Date:** 2026-02-25
**Status:** Draft
**Discussion:** [Link to GitHub Discussion]

## Summary

This proposal outlines enhancements to Quarkus Dev Mode's MCP (Model Context Protocol) integration to make it more suitable and productive for AI coding agents. The goal is to transform Quarkus into a first-class development environment for AI-assisted coding workflows.

## Motivation

AI coding agents (Claude, GitHub Copilot, Cursor, Cline, etc.) are increasingly used for software development. These agents work best when they have:

1. **Structured feedback** - Machine-parseable errors, not just text
2. **Deep context** - Understanding of application structure, not just source files
3. **Real-time updates** - Push notifications, not polling
4. **Safe mutation capabilities** - Ability to make changes with guardrails
5. **Incremental information** - What changed, not full state dumps

Quarkus Dev Mode is uniquely positioned to provide all of this. The build-time augmentation model means Quarkus *already knows* everything about the application - beans, endpoints, configuration, dependencies, etc. The MCP integration (introduced in Quarkus 3.x) provides the protocol layer. What's missing is exposing this rich internal state in AI-optimized formats.

## Current State

The existing MCP implementation (`/q/dev-mcp`) provides:

- ✅ Tool discovery and execution via JSON-RPC methods
- ✅ Resource exposure for build-time data
- ✅ IDE configuration templates
- ✅ Enable/disable controls for tools and resources

Key gaps:

- ❌ No structured error reporting
- ❌ No push notifications (SSE planned but not implemented)
- ❌ Limited project context exposure
- ❌ No mutation tools with preview/rollback
- ❌ No session or incremental change tracking

## Proposed Enhancements

### 1. Structured Error Feedback

**Goal:** Provide machine-parseable error information that AI agents can act on immediately.

#### New MCP Resources

| Resource URI | Description |
|--------------|-------------|
| `quarkus://errors/compilation` | Compilation errors with file:line:column |
| `quarkus://errors/tests` | Test failures with structured details |
| `quarkus://errors/build` | Build/augmentation failures |
| `quarkus://errors/runtime` | Runtime exceptions from dev mode |

#### Error Schema

```json
{
  "errors": [
    {
      "type": "compilation",
      "severity": "error",
      "code": "compiler.err.cant.resolve",
      "message": "cannot find symbol: class NonExistent",
      "location": {
        "file": "src/main/java/com/example/MyResource.java",
        "line": 42,
        "column": 15,
        "endLine": 42,
        "endColumn": 26
      },
      "context": {
        "sourceSnippet": "    private NonExistent service;",
        "surroundingLines": 3
      },
      "suggestions": [
        "Did you mean 'Existent'?",
        "Add import: com.other.NonExistent"
      ],
      "relatedErrors": []
    }
  ],
  "summary": {
    "total": 1,
    "byType": {"compilation": 1},
    "bySeverity": {"error": 1}
  }
}
```

#### Implementation Notes

- Hook into `QuarkusCompiler` for compilation errors
- Integrate with `TestSupport` for test results
- Capture `AugmentException` details during build
- Store errors in a ring buffer, expose via resource

### 2. Project Context Resources

**Goal:** Expose deep application structure that AI agents need for informed suggestions.

#### New MCP Resources

| Resource URI | Description |
|--------------|-------------|
| `quarkus://context/beans` | CDI beans with injection points, scopes, qualifiers |
| `quarkus://context/endpoints` | REST endpoints with full parameter schemas |
| `quarkus://context/config` | Configuration properties with values, sources, metadata |
| `quarkus://context/config/schema` | Config property definitions and constraints |
| `quarkus://context/dependencies` | Dependency tree with versions |
| `quarkus://context/entities` | JPA/Panache entities with mappings |
| `quarkus://context/extensions` | Enabled extensions with capabilities |
| `quarkus://context/build-items` | Build items produced during augmentation |

#### Bean Context Schema

```json
{
  "beans": [
    {
      "id": "GreetingService_Bean",
      "className": "com.example.GreetingService",
      "kind": "CLASS",
      "scope": "ApplicationScoped",
      "qualifiers": ["@Default"],
      "stereotypes": [],
      "injectionPoints": [
        {
          "target": "field",
          "name": "config",
          "type": "com.example.GreetingConfig",
          "qualifiers": ["@Default"],
          "required": true,
          "resolvedBean": "GreetingConfig_Bean"
        }
      ],
      "producerMethods": [],
      "observerMethods": [
        {
          "method": "onStartup",
          "eventType": "io.quarkus.runtime.StartupEvent",
          "priority": 0
        }
      ],
      "interceptors": ["LoggingInterceptor"],
      "sourceLocation": {
        "file": "src/main/java/com/example/GreetingService.java",
        "line": 15
      }
    }
  ],
  "summary": {
    "total": 45,
    "byScope": {"ApplicationScoped": 30, "RequestScoped": 10, "Dependent": 5}
  }
}
```

#### Endpoint Context Schema

```json
{
  "endpoints": [
    {
      "path": "/api/greeting/{name}",
      "method": "GET",
      "produces": ["application/json"],
      "consumes": [],
      "parameters": [
        {
          "name": "name",
          "in": "path",
          "type": "string",
          "required": true
        },
        {
          "name": "formal",
          "in": "query",
          "type": "boolean",
          "required": false,
          "defaultValue": "false"
        }
      ],
      "returnType": "com.example.Greeting",
      "returnSchema": {
        "type": "object",
        "properties": {
          "message": {"type": "string"},
          "timestamp": {"type": "string", "format": "date-time"}
        }
      },
      "handlerClass": "com.example.GreetingResource",
      "handlerMethod": "greet",
      "sourceLocation": {
        "file": "src/main/java/com/example/GreetingResource.java",
        "line": 25
      },
      "security": {
        "authenticated": false,
        "rolesAllowed": []
      }
    }
  ]
}
```

#### Implementation Notes

- Bean data available from `BeanInfo` during augmentation
- Endpoint data from `ResourceMethodBuildItem` and similar
- Config metadata from `ConfigDescriptionBuildItem`
- Cache at build time, expose as MCP resources
- Consider lazy loading for large datasets

### 3. Real-Time Notifications (SSE)

**Goal:** Push changes to AI agents instead of requiring polling.

#### New MCP Notifications

| Notification | Trigger |
|--------------|---------|
| `quarkus/hotReloadStarted` | File change detected |
| `quarkus/hotReloadCompleted` | Hot reload finished (success or failure) |
| `quarkus/testRunCompleted` | Continuous testing finished |
| `quarkus/buildPhaseChanged` | Build phase transition |
| `quarkus/logEntry` | Log message (filterable by level) |
| `quarkus/resourceChanged` | Watched resource changed |

#### Notification Payloads

**Hot Reload Completed:**
```json
{
  "jsonrpc": "2.0",
  "method": "notifications/message",
  "params": {
    "type": "quarkus/hotReloadCompleted",
    "data": {
      "id": "reload-123",
      "timestamp": "2026-02-25T10:30:00.000Z",
      "trigger": {
        "files": ["src/main/java/com/example/MyResource.java"]
      },
      "result": {
        "success": true,
        "duration_ms": 450,
        "reloadedClasses": ["com.example.MyResource"],
        "warnings": []
      },
      "context": {
        "totalReloads": 42,
        "sessionStart": "2026-02-25T09:00:00.000Z"
      }
    }
  }
}
```

**Test Run Completed:**
```json
{
  "jsonrpc": "2.0",
  "method": "notifications/message",
  "params": {
    "type": "quarkus/testRunCompleted",
    "data": {
      "id": "test-456",
      "timestamp": "2026-02-25T10:30:05.000Z",
      "trigger": "hot-reload",
      "summary": {
        "total": 45,
        "passed": 43,
        "failed": 1,
        "skipped": 1,
        "duration_ms": 2500
      },
      "failures": [
        {
          "test": "com.example.GreetingResourceTest#testHelloEndpoint",
          "message": "Expected status 200 but was 404",
          "type": "assertion",
          "location": {
            "file": "src/test/java/com/example/GreetingResourceTest.java",
            "line": 32
          },
          "stackTrace": "..."
        }
      ]
    }
  }
}
```

#### Implementation Notes

- Complete SSE support in `McpHttpHandler` (currently stubbed)
- Subscribe to `HotReplacementContext` for reload events
- Integrate with `TestSupport` for test completion
- Consider WebSocket as alternative transport
- Implement client subscription management

### 4. Safe Mutation Tools

**Goal:** Enable AI agents to make changes with preview and rollback capabilities.

#### New MCP Tools

| Tool | Risk Level | Description |
|------|------------|-------------|
| `quarkus/config/get` | read-only | Get configuration value with metadata |
| `quarkus/config/set` | reversible | Update configuration property |
| `quarkus/config/preview` | read-only | Preview effect of config change |
| `quarkus/test/run` | read-only | Run specific test(s) |
| `quarkus/test/runAffected` | read-only | Run tests affected by recent changes |
| `quarkus/build/compile` | read-only | Trigger compilation check |
| `quarkus/rollback` | reversible | Rollback previous change |

#### Tool Schemas

**config/set:**
```json
{
  "name": "quarkus/config/set",
  "description": "Update a configuration property value",
  "inputSchema": {
    "type": "object",
    "properties": {
      "key": {
        "type": "string",
        "description": "Configuration property key (e.g., quarkus.http.port)"
      },
      "value": {
        "type": "string",
        "description": "New value for the property"
      },
      "scope": {
        "type": "string",
        "enum": ["runtime", "persistent"],
        "default": "runtime",
        "description": "runtime = in-memory only, persistent = write to application.properties"
      },
      "preview": {
        "type": "boolean",
        "default": false,
        "description": "If true, return preview of changes without applying"
      }
    },
    "required": ["key", "value"]
  }
}
```

**config/set response (preview mode):**
```json
{
  "preview": true,
  "changes": {
    "property": "quarkus.datasource.jdbc.url",
    "oldValue": "jdbc:h2:mem:test",
    "newValue": "jdbc:postgresql://localhost/mydb",
    "source": "application.properties:15"
  },
  "impact": {
    "affectedBeans": ["DefaultDataSource", "UserRepository", "OrderRepository"],
    "requiresRestart": false,
    "warnings": [
      "Database connection pool will be recreated",
      "PostgreSQL driver must be available"
    ]
  },
  "validation": {
    "valid": true,
    "errors": []
  }
}
```

**test/run:**
```json
{
  "name": "quarkus/test/run",
  "description": "Run specific test class or method",
  "inputSchema": {
    "type": "object",
    "properties": {
      "target": {
        "type": "string",
        "description": "Test class or method (e.g., com.example.MyTest or com.example.MyTest#testMethod)"
      },
      "tags": {
        "type": "array",
        "items": {"type": "string"},
        "description": "JUnit tags to include"
      }
    },
    "required": ["target"]
  }
}
```

#### Implementation Notes

- Leverage existing dev mode config update mechanisms
- Integrate with continuous testing for selective test runs
- Implement audit logging for all mutations
- Store rollback information for reversible operations

### 5. Audit Log and Rollback

**Goal:** Track all changes and enable rollback.

#### New MCP Resource

```
quarkus://audit/log
quarkus://audit/log/{entryId}
```

#### Audit Log Schema

```json
{
  "entries": [
    {
      "id": "audit-789",
      "timestamp": "2026-02-25T10:30:00.000Z",
      "client": {
        "name": "claude-code",
        "version": "1.0.0",
        "sessionId": "session-123"
      },
      "tool": "quarkus/config/set",
      "parameters": {
        "key": "quarkus.http.port",
        "value": "8081"
      },
      "result": {
        "success": true,
        "previousValue": "8080"
      },
      "rollback": {
        "available": true,
        "expires": "2026-02-25T11:30:00.000Z"
      }
    }
  ],
  "pagination": {
    "offset": 0,
    "limit": 50,
    "total": 123
  }
}
```

#### Rollback Tool

```json
{
  "name": "quarkus/rollback",
  "description": "Rollback a previous change",
  "inputSchema": {
    "type": "object",
    "properties": {
      "auditId": {
        "type": "string",
        "description": "Audit log entry ID to rollback"
      },
      "scope": {
        "type": "string",
        "enum": ["single", "cascade"],
        "default": "single",
        "description": "single = just this change, cascade = this and all subsequent"
      }
    },
    "required": ["auditId"]
  }
}
```

### 6. Incremental Change Tracking

**Goal:** Provide delta information instead of full state dumps to reduce token usage.

#### New MCP Resources

```
quarkus://changes/since/{timestamp}
quarkus://changes/since/{hotReloadId}
quarkus://changes/latest
```

#### Change Delta Schema

```json
{
  "baseline": {
    "type": "hotReloadId",
    "value": "reload-120",
    "timestamp": "2026-02-25T10:25:00.000Z"
  },
  "current": {
    "hotReloadId": "reload-123",
    "timestamp": "2026-02-25T10:30:00.000Z"
  },
  "changes": {
    "files": {
      "modified": ["src/main/java/com/example/MyResource.java"],
      "added": ["src/main/java/com/example/NewService.java"],
      "deleted": []
    },
    "beans": {
      "added": ["NewService_Bean"],
      "removed": [],
      "modified": ["MyResource_Bean"]
    },
    "endpoints": {
      "added": [{"path": "/api/new", "method": "POST"}],
      "removed": [],
      "modified": [{"path": "/api/greeting/{name}", "changes": ["returnType"]}]
    },
    "config": {
      "changed": [
        {
          "key": "quarkus.http.port",
          "oldValue": "8080",
          "newValue": "8081"
        }
      ]
    },
    "errors": {
      "added": [],
      "resolved": ["compilation error in MyResource.java:42"]
    }
  }
}
```

#### Implementation Notes

- Maintain version vector for each resource type
- Store snapshots at hot reload boundaries
- Implement efficient diff computation
- Consider memory limits for snapshot retention

### 7. Session Management

**Goal:** Track agent sessions for better context and resource management.

#### New MCP Methods

**session/create:**
```json
{
  "method": "session/create",
  "params": {
    "clientInfo": {
      "name": "claude-code",
      "version": "1.0.0"
    },
    "capabilities": {
      "notifications": true,
      "streaming": true
    },
    "preferences": {
      "errorFormat": "detailed",
      "includeSourceSnippets": true
    }
  }
}
```

**Response:**
```json
{
  "sessionId": "session-abc123",
  "created": "2026-02-25T10:00:00.000Z",
  "expires": "2026-02-25T22:00:00.000Z",
  "serverCapabilities": {
    "notifications": ["hotReload", "testRun", "errors"],
    "tools": ["config/*", "test/*"],
    "resources": ["context/*", "errors/*", "changes/*"]
  }
}
```

#### Resource Watching

```json
{
  "method": "resources/watch",
  "params": {
    "sessionId": "session-abc123",
    "resources": [
      "quarkus://errors/*",
      "quarkus://context/endpoints"
    ]
  }
}
```

Changes to watched resources trigger notifications via SSE.

### 8. Documentation Resources

**Goal:** Provide contextual Quarkus documentation to AI agents.

#### New MCP Resources

| Resource URI | Description |
|--------------|-------------|
| `quarkus://docs/extensions/{name}` | Extension guide and examples |
| `quarkus://docs/config/{prefix}` | Config property documentation |
| `quarkus://docs/guides/{topic}` | Relevant guides for current context |
| `quarkus://docs/api/{className}` | API documentation |

#### Extension Documentation Schema

```json
{
  "extension": "quarkus-rest",
  "groupId": "io.quarkus",
  "version": "3.17.0",
  "summary": "Build RESTful web services using Jakarta REST (formerly JAX-RS)",
  "guide": "https://quarkus.io/guides/rest",
  "quickstart": {
    "dependency": "<dependency>\n  <groupId>io.quarkus</groupId>\n  <artifactId>quarkus-rest</artifactId>\n</dependency>",
    "example": "@Path(\"/hello\")\npublic class GreetingResource {\n    @GET\n    public String hello() {\n        return \"Hello\";\n    }\n}"
  },
  "configProperties": [
    {
      "key": "quarkus.rest.path",
      "type": "string",
      "default": "/",
      "description": "The REST root path"
    }
  ],
  "commonPatterns": [
    {
      "name": "Exception Mapper",
      "description": "Handle exceptions globally",
      "code": "@Provider\npublic class ErrorMapper implements ExceptionMapper<Exception> {...}"
    },
    {
      "name": "Request Filter",
      "description": "Intercept incoming requests",
      "code": "@Provider\npublic class LoggingFilter implements ContainerRequestFilter {...}"
    }
  ],
  "relatedExtensions": ["quarkus-rest-jackson", "quarkus-rest-client", "quarkus-smallrye-openapi"]
}
```

#### Contextual Help Tool

```json
{
  "name": "quarkus/help/suggest",
  "description": "Get contextual help based on current situation",
  "inputSchema": {
    "type": "object",
    "properties": {
      "context": {
        "type": "string",
        "description": "What the developer is trying to accomplish"
      },
      "currentFile": {
        "type": "string",
        "description": "File currently being edited"
      },
      "error": {
        "type": "string",
        "description": "Error message if troubleshooting"
      }
    }
  }
}
```

## Configuration

### Application Properties

```properties
# Enable/disable MCP server (default: true in dev mode)
quarkus.dev-mcp.enabled=true

# Security settings
quarkus.dev-mcp.cors.origins=http://localhost:*
quarkus.dev-mcp.require-localhost=true

# Tool restrictions
quarkus.dev-mcp.tools.allowed=config/*,test/*,help/*
quarkus.dev-mcp.tools.blocked=

# Resource restrictions
quarkus.dev-mcp.resources.allowed=*
quarkus.dev-mcp.resources.blocked=

# Audit settings
quarkus.dev-mcp.audit.enabled=true
quarkus.dev-mcp.audit.retention=1h

# Session settings
quarkus.dev-mcp.session.timeout=12h
quarkus.dev-mcp.session.max-per-client=5

# Notification settings
quarkus.dev-mcp.notifications.enabled=true
quarkus.dev-mcp.notifications.buffer-size=100
```

## Security Considerations

1. **Localhost only by default** - MCP endpoint should only bind to localhost
2. **No production exposure** - MCP is strictly dev mode only
3. **Tool risk classification** - Mark tools as read-only, reversible, or destructive
4. **Audit logging** - All mutations logged with client info
5. **Rate limiting** - Prevent runaway AI loops
6. **Sensitive config masking** - Hide passwords, secrets in responses

## Backwards Compatibility

All proposed features are additive. Existing MCP clients will continue to work. New features are opt-in via:

- New resource URIs (clients that don't know about them won't request them)
- New tools (disabled by default, require explicit enablement)
- SSE notifications (only sent to clients that establish SSE connection)

## Implementation Phases

### Phase 1: Foundation (Recommended First)
- Structured error feedback resources
- SSE notification infrastructure
- Basic audit logging

### Phase 2: Context Enrichment
- Bean context resource
- Endpoint context resource
- Config context resource

### Phase 3: Safe Mutations
- Config preview/set tools
- Test execution tools
- Rollback mechanism

### Phase 4: Advanced Features
- Incremental change tracking
- Session management
- Documentation resources

## Success Metrics

1. **Error resolution speed** - Time from error to fix with AI assistance
2. **Context fetch reduction** - Fewer file reads needed by AI agents
3. **Reload cycles** - Fewer failed hot reloads due to AI mistakes
4. **Token efficiency** - Reduced context size for AI interactions

## Open Questions

1. Should we support multiple concurrent AI agent sessions?
2. What's the right granularity for change notifications?
3. How much documentation should be embedded vs. fetched?
4. Should we provide code generation tools (REST client scaffolding, etc.)?
5. Integration with Quarkus CLI for offline/headless AI workflows?

## References

- [MCP Specification](https://modelcontextprotocol.io/)
- [Quarkus Dev MCP Documentation](https://quarkus.io/guides/dev-mcp)
- [Quarkus Dev Mode Architecture](https://quarkus.io/guides/writing-extensions#dev-mode)

## Appendix: Tool Risk Classification

```java
public enum ToolRisk {
    /**
     * Tool only reads data, no side effects.
     * Examples: get config, list beans, read errors
     */
    READ_ONLY,

    /**
     * Tool makes changes that can be automatically rolled back.
     * Examples: set config (in-memory), toggle feature flag
     */
    REVERSIBLE,

    /**
     * Tool makes changes that require manual intervention to undo.
     * Examples: write to file, modify persistent config
     */
    PERSISTENT,

    /**
     * Tool makes changes that cannot be undone.
     * Examples: delete data, clear cache
     */
    DESTRUCTIVE
}
```

AI agents can use this classification to:
- Prefer read-only operations when exploring
- Request confirmation for destructive operations
- Track reversible operations for potential rollback

---

*This proposal is open for discussion. Please share feedback, concerns, and additional ideas.*
