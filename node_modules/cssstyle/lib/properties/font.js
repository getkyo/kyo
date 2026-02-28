"use strict";

const parsers = require("../parsers");
const fontStyle = require("./fontStyle");
const fontVariant = require("./fontVariant");
const fontWeight = require("./fontWeight");
const fontSize = require("./fontSize");
const lineHeight = require("./lineHeight");
const fontFamily = require("./fontFamily");

const property = "font";

module.exports.shorthandFor = new Map([
  [fontStyle.property, fontStyle],
  [fontVariant.property, fontVariant],
  [fontWeight.property, fontWeight],
  [fontSize.property, fontSize],
  [lineHeight.property, lineHeight],
  [fontFamily.property, fontFamily]
]);

module.exports.parse = (v, opt = {}) => {
  const { globalObject } = opt;
  if (v === "") {
    return v;
  } else if (parsers.hasCalcFunc(v)) {
    v = parsers.resolveCalc(v);
  }
  if (!parsers.isValidPropertyValue(property, v)) {
    return;
  }
  const { AST_TYPES } = parsers;
  const [fontBlock, ...families] = parsers.splitValue(v, {
    delimiter: ","
  });
  const [fontBlockA, fontBlockB] = parsers.splitValue(fontBlock, {
    delimiter: "/"
  });
  const font = {
    [fontStyle.property]: "normal",
    [fontVariant.property]: "normal",
    [fontWeight.property]: "normal"
  };
  const fontFamilies = new Set();
  if (fontBlockB) {
    const [lineB, ...familiesB] = fontBlockB.trim().split(" ");
    if (!lineB || !familiesB.length) {
      return;
    }
    const lineHeightB = lineHeight.parse(lineB, {
      global
    });
    if (typeof lineHeightB !== "string") {
      return;
    }
    const familyB = fontFamily.parse(familiesB.join(" "), {
      globalObject,
      caseSensitive: true
    });
    if (typeof familyB === "string") {
      fontFamilies.add(familyB);
    } else {
      return;
    }
    const parts = parsers.splitValue(fontBlockA.trim());
    const properties = [
      fontStyle.property,
      fontVariant.property,
      fontWeight.property,
      fontSize.property
    ];
    for (const part of parts) {
      if (part === "normal") {
        continue;
      } else {
        for (const longhand of properties) {
          switch (longhand) {
            case fontSize.property: {
              const parsedValue = fontSize.parse(part, {
                globalObject
              });
              if (typeof parsedValue === "string") {
                font[longhand] = parsedValue;
              }
              break;
            }
            case fontStyle.property:
            case fontWeight.property: {
              if (font[longhand] === "normal") {
                const longhandItem = module.exports.shorthandFor.get(longhand);
                const parsedValue = longhandItem.parse(part, {
                  globalObject
                });
                if (typeof parsedValue === "string") {
                  font[longhand] = parsedValue;
                }
              }
              break;
            }
            case fontVariant.property: {
              if (font[longhand] === "normal") {
                const parsedValue = fontVariant.parse(part, {
                  globalObject
                });
                if (typeof parsedValue === "string") {
                  if (parsedValue === "small-cap") {
                    font[longhand] = parsedValue;
                  } else if (parsedValue !== "normal") {
                    return;
                  }
                }
              }
              break;
            }
            default:
          }
        }
      }
    }
    if (Object.hasOwn(font, fontSize.property)) {
      font[lineHeight.property] = lineHeightB;
    } else {
      return;
    }
  } else {
    const revParts = parsers.splitValue(fontBlockA.trim()).toReversed();
    if (revParts.length === 1) {
      const [part] = revParts;
      const value = parsers.parsePropertyValue(property, part, {
        globalObject,
        inArray: true
      });
      if (Array.isArray(value) && value.length === 1) {
        const [{ name, type }] = value;
        if (type === AST_TYPES.GLOBAL_KEYWORD) {
          return {
            [fontStyle.property]: name,
            [fontVariant.property]: name,
            [fontWeight.property]: name,
            [fontSize.property]: name,
            [lineHeight.property]: name,
            [fontFamily.property]: name
          };
        }
      }
      return;
    }
    const properties = [
      fontStyle.property,
      fontVariant.property,
      fontWeight.property,
      lineHeight.property
    ];
    for (const longhand of properties) {
      font[longhand] = "normal";
    }
    const revFontFamily = [];
    let fontSizeA;
    for (const part of revParts) {
      if (fontSizeA) {
        if (/^normal$/i.test(part)) {
          continue;
        } else {
          for (const longhand of properties) {
            switch (longhand) {
              case fontStyle.property:
              case fontWeight.property:
              case lineHeight.property: {
                if (font[longhand] === "normal") {
                  const longhandItem = module.exports.shorthandFor.get(longhand);
                  const parsedValue = longhandItem.parse(part, {
                    globalObject
                  });
                  if (typeof parsedValue === "string") {
                    font[longhand] = parsedValue;
                  }
                }
                break;
              }
              case fontVariant.property: {
                if (font[longhand] === "normal") {
                  const parsedValue = fontVariant.parse(part, {
                    globalObject
                  });
                  if (typeof parsedValue === "string") {
                    if (parsedValue === "small-cap") {
                      font[longhand] = parsedValue;
                    } else if (parsedValue !== "normal") {
                      return;
                    }
                  }
                }
                break;
              }
              default:
            }
          }
        }
      } else {
        const parsedFontSize = fontSize.parse(part, {
          globalObject
        });
        if (typeof parsedFontSize === "string") {
          fontSizeA = parsedFontSize;
        } else {
          const parsedFontFamily = fontFamily.parse(part, {
            globalObject,
            caseSensitive: true
          });
          if (typeof parsedFontFamily === "string") {
            revFontFamily.push(parsedFontFamily);
          } else {
            return;
          }
        }
      }
    }
    const family = fontFamily.parse(revFontFamily.toReversed().join(" "), {
      globalObject,
      caseSensitive: true
    });
    if (fontSizeA && family) {
      font[fontSize.property] = fontSizeA;
      fontFamilies.add(fontFamily.parse(family));
    } else {
      return;
    }
  }
  for (const family of families) {
    const parsedFontFamily = fontFamily.parse(family, {
      globalObject,
      caseSensitive: true
    });
    if (parsedFontFamily) {
      fontFamilies.add(parsedFontFamily);
    } else {
      return;
    }
  }
  font[fontFamily.property] = [...fontFamilies].join(", ");
  return font;
};

module.exports.definition = {
  set(v) {
    v = parsers.prepareValue(v);
    if (v === "" || parsers.hasVarFunc(v)) {
      for (const [key] of module.exports.shorthandFor) {
        this._setProperty(key, "");
      }
      this._setProperty(property, v);
    } else {
      const obj = module.exports.parse(v, {
        globalObject: this._global
      });
      if (!obj) {
        return;
      }
      const priority = this._priorities.get(property) ?? "";
      const str = new Set();
      for (const [key] of module.exports.shorthandFor) {
        const val = obj[key];
        if (typeof val === "string") {
          this._setProperty(key, val, priority);
          if (val && val !== "normal" && !str.has(val)) {
            if (key === lineHeight.property) {
              str.add(`/ ${val}`);
            } else {
              str.add(val);
            }
          }
        }
      }
      this._setProperty(property, [...str].join(" "), priority);
    }
  },
  get() {
    const val = this.getPropertyValue(property);
    if (parsers.hasVarFunc(val)) {
      return val;
    }
    const str = new Set();
    for (const [key] of module.exports.shorthandFor) {
      const v = this.getPropertyValue(key);
      if (parsers.hasVarFunc(v)) {
        return "";
      }
      if (v && v !== "normal" && !str.has(v)) {
        if (key === lineHeight.property) {
          str.add(`/ ${v}`);
        } else {
          str.add(`${v}`);
        }
      }
    }
    return [...str].join(" ");
  },
  enumerable: true,
  configurable: true
};

module.exports.property = property;
