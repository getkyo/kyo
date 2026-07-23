package kyo.ai.completion

import kyo.*
import kyo.ai.Config

class ClaudeCodeCompletionTest extends kyo.test.Test[Any]:

    "strippedEnvVars is exactly the three ambient API credential vars (subscription-guarantee isolation)" in {
        // The base URL is deliberately NOT in the set: it is routing, not a credential. Stripping it
        // made Config.apiUrl silently inert on this backend alone while the native path honored it.
        assert(
            ClaudeCodeCompletion.strippedEnvVars ==
                Set("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_API_KEY"),
            s"ambient credentials that could divert billing to a metered key must stay out of the CLI " +
                s"session by default: ${ClaudeCodeCompletion.strippedEnvVars}"
        )
        assert(
            !ClaudeCodeCompletion.strippedEnvVars.contains("ANTHROPIC_BASE_URL"),
            "the base URL is routing, not a credential; it must survive into the CLI session"
        )
    }

    "claudeCommand carries the default reasoning budget by default and none when disabled" in {
        val default = ClaudeCodeCompletion.claudeCommand(Config.ClaudeCode.sonnet, Chunk("claude", "-p"))
        assert(
            default.env.get("MAX_THINKING_TOKENS").contains(Config.defaultReasoningBudget.toString),
            s"thinking is on by default, so the CLI budget must ride MAX_THINKING_TOKENS: ${default.env}"
        )
        val disabled = ClaudeCodeCompletion.claudeCommand(Config.ClaudeCode.sonnet.disableReasoning, Chunk("claude", "-p"))
        assert(
            !disabled.env.contains("MAX_THINKING_TOKENS"),
            s"a disabled budget must not ride the budget variable: ${disabled.env}"
        )
        // Turning reasoning off has to be stated: saying nothing leaves the harness reasoning under a
        // ceiling sized for a reply that does not reason, which is how a reply stops before producing
        // anything. The earlier expectation here asserted the switch did nothing.
        assert(
            disabled.env.get("CLAUDE_CODE_DISABLE_THINKING").contains("1"),
            s"disableReasoning must actually disable reasoning on this path: ${disabled.env}"
        )
        assert(
            !default.env.contains("CLAUDE_CODE_DISABLE_THINKING"),
            s"the disable switch must not ride when reasoning is on: ${default.env}"
        )
    }

    "claudeCommand carries the thinking budget as MAX_THINKING_TOKENS and the effective cap as CLAUDE_CODE_MAX_OUTPUT_TOKENS" in {
        val config  = Config.ClaudeCode.sonnet.reasoningBudget(12000).maxTokens(5000)
        val command = ClaudeCodeCompletion.claudeCommand(config, Chunk("claude", "-p"))
        val env     = command.env
        assert(env.get("MAX_THINKING_TOKENS").contains("12000"), s"the thinking budget must ride MAX_THINKING_TOKENS: $env")
        assert(
            env.get("CLAUDE_CODE_MAX_OUTPUT_TOKENS").contains(config.effectiveMaxOutputTokens.toString),
            s"the effective output cap must ride CLAUDE_CODE_MAX_OUTPUT_TOKENS: $env"
        )
        assert(
            env.get("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC").contains("1"),
            s"the session-title side request must stay disabled so both backends make one request per generation: $env"
        )
        assert(
            env.get("CLAUDE_CODE_DISABLE_AUTO_MEMORY").contains("1"),
            s"the CLI's project-memory auto-load must stay disabled so the session sees only the kyo-built context: $env"
        )
    }

    "claudeCommand carries a non-empty Config.apiUrl as ANTHROPIC_BASE_URL and sets none by default" in {
        val custom = ClaudeCodeCompletion.claudeCommand(
            Config.ClaudeCode.sonnet.apiUrl("http://127.0.0.1:8788"),
            Chunk("claude", "-p")
        )
        assert(
            custom.env.get("ANTHROPIC_BASE_URL").contains("http://127.0.0.1:8788"),
            s"a configured apiUrl must ride ANTHROPIC_BASE_URL: ${custom.env}"
        )
        val default = ClaudeCodeCompletion.claudeCommand(Config.ClaudeCode.sonnet, Chunk("claude", "-p"))
        assert(
            !default.env.contains("ANTHROPIC_BASE_URL"),
            s"the empty provider-default apiUrl must set no base URL (the ambient one passes through): ${default.env}"
        )
    }

    "the user-tool MCP bridge partition excludes the result tool (transport isolation)" in {
        // userToolInfos is the exact function withMcpBridge calls to split the plain per-tool MCP handlers
        // from the result tool, which rides resultToolHandler instead. The live bridge wiring itself (the
        // result tool never double-registered as a user tool over a real MCP connection) is exercised end
        // to end by CompletionIntegrationTest's harness-pair-equality scenario against the real CLI.
        val userTool   = Tool.init[Int]("lookup", "a lookup tool")(_ => 1)
        val resultTool = Tool.internal.resultToolDefinition
        val tools      = resultTool.infos.concat(userTool.infos)

        val partitioned = ClaudeCodeCompletion.userToolInfos(tools)

        assert(partitioned.map(_.name) == Chunk("lookup"), s"the result tool must not ride the user-tool handler set: $partitioned")
        assert(tools.exists(_.name == Completion.resultToolName), "the result tool must still be present in the full tool set")
    }

end ClaudeCodeCompletionTest
