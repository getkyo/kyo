// flow-allow: PUBLIC config-strategy sum type referenced by JsonRpcEndpoint.Config.idStrategy field
package kyo

enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end IdStrategy
