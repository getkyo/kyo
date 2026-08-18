## kernel (EA on) vs kernel (EA off)
| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
| ⚪ | `evalFixedOverhead` | avgt | 5 | 0.007378 ± 0.000097 | 0.007369 ± 0.000083 | -0.1% | ±4.0% | -0 | - |
| ⚪ | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 6.00 ± 0.2405 | 6.16 ± 0.0431 | +2.8% | ±4.0% | +0 | - |

## kernel (EA off) vs proto (EA off)
| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
| 🔴 | `evalFixedOverhead` | avgt | 5 | 0.007369 ± 0.000083 | 0.008605 ± 0.000220 | +16.8% | ±4.0% | +40 | allocation +40 B/op |
| 🔴 | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 6.16 ± 0.0431 | 8.65 ± 0.0620 | +40.3% | ±4.0% | +48024 | allocation +48024 B/op |

## proto (EA on) vs proto (EA off)
| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
| ⚪ | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 8.41 ± 0.0957 | 8.65 ± 0.0620 | +2.9% | ±4.0% | +24024 | - |
| 🔴 | `evalFixedOverhead` | avgt | 5 | 0.004009 ± 0.000033 | 0.008605 ± 0.000220 | +114.7% | ±4.0% | +40 | allocation +40 B/op |
