"use strict";

const parsers = require("../parsers");

const property = "font-family";
const shorthand = "font";

module.exports.parse = (v, opt = {}) => {
  const { globalObject } = opt;
  if (v === "") {
    return v;
  }
  const { AST_TYPES } = parsers;
  const values = parsers.splitValue(v, {
    delimiter: ","
  });
  const parsedValues = [];
  for (const val of values) {
    const value = parsers.parsePropertyValue(property, val, {
      globalObject,
      caseSensitive: true,
      inArray: true
    });
    if (Array.isArray(value) && value.length) {
      if (value.length === 1) {
        const [{ name, type, value: itemValue }] = value;
        switch (type) {
          case AST_TYPES.FUNCTION: {
            parsedValues.push(`${name}(${itemValue})`);
            break;
          }
          case AST_TYPES.GLOBAL_KEYWORD:
          case AST_TYPES.IDENTIFIER: {
            if (name === "undefined") {
              return;
            }
            parsedValues.push(name);
            break;
          }
          case "String": {
            const parsedValue = itemValue.replaceAll("\\", "").replaceAll('"', '\\"');
            parsedValues.push(`"${parsedValue}"`);
            break;
          }
          default: {
            return;
          }
        }
      } else {
        const parts = [];
        for (const item of value) {
          const { name, type } = item;
          if (type !== AST_TYPES.IDENTIFIER) {
            return;
          }
          parts.push(name);
        }
        const parsedValue = parts.join(" ").replaceAll("\\", "").replaceAll('"', '\\"');
        parsedValues.push(`"${parsedValue}"`);
      }
    } else if (typeof value === "string") {
      parsedValues.push(value);
    } else {
      return;
    }
  }
  if (parsedValues.length) {
    return parsedValues.join(", ");
  }
};

module.exports.definition = {
  set(v) {
    v = parsers.prepareValue(v);
    if (parsers.hasVarFunc(v)) {
      this._setProperty(shorthand, "");
      this._setProperty(property, v);
    } else {
      const val = module.exports.parse(v, {
        globalObject: this._global
      });
      if (typeof val === "string") {
        const priority =
          !this._priorities.get(shorthand) && this._priorities.has(property)
            ? this._priorities.get(property)
            : "";
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
