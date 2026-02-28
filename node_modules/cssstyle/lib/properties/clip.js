"use strict";
// deprecated
// @see https://drafts.csswg.org/css-masking-1/#clip-property

const parsers = require("../parsers");

const property = "clip";

module.exports.parse = (v, opt = {}) => {
  const { globalObject } = opt;
  if (v === "") {
    return v;
  }
  const { AST_TYPES } = parsers;
  const value = parsers.parsePropertyValue(property, v, {
    globalObject,
    inArray: true
  });
  if (Array.isArray(value) && value.length === 1) {
    const [{ name, type, value: itemValue }] = value;
    switch (type) {
      case AST_TYPES.FUNCTION: {
        const values = parsers.splitValue(itemValue, {
          delimiter: ","
        });
        const parsedValues = [];
        for (const item of values) {
          const parsedValue = parsers.parseCSS(item, { context: "value" }, true);
          const val = parsers.resolveNumericValue(parsedValue.children, {
            type: "length"
          });
          if (val) {
            parsedValues.push(val);
          } else {
            return;
          }
        }
        return `${name}(${parsedValues.join(", ")})`;
      }
      case AST_TYPES.GLOBAL_KEYWORD:
      case AST_TYPES.IDENTIFIER: {
        return name;
      }
      default:
    }
  } else if (typeof value === "string") {
    return value;
  }
};

module.exports.definition = {
  set(v) {
    v = parsers.prepareValue(v);
    if (parsers.hasVarFunc(v)) {
      this._setProperty(property, v);
    } else {
      const val = module.exports.parse(v, {
        globalObject: this._global
      });
      if (typeof val === "string") {
        const priority = this._priorities.get(property) ?? "";
        this._setProperty(property, val, priority);
      }
    }
  },
  get() {
    return this.getPropertyValue(property);
  },
  enumerable: true,
  configurable: true
};

module.exports.property = property;
