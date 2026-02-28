"use strict";

const parsers = require("../parsers");

const property = "background-size";
const shorthand = "background";

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
      inArray: true
    });
    if (Array.isArray(value) && value.length) {
      if (value.length === 1) {
        const [{ isNumber, name, type, value: itemValue }] = value;
        switch (type) {
          case AST_TYPES.CALC: {
            if (isNumber) {
              return;
            }
            parsedValues.push(`${name}(${itemValue})`);
            break;
          }
          case AST_TYPES.GLOBAL_KEYWORD:
          case AST_TYPES.IDENTIFIER: {
            parsedValues.push(name);
            break;
          }
          default: {
            const parsedValue = parsers.resolveNumericValue(value, {
              type: "length"
            });
            if (!parsedValue) {
              return;
            }
            parsedValues.push(parsedValue);
          }
        }
      } else {
        const [val1, val2] = value;
        const parts = [];
        if (val1.type === AST_TYPES.CALC && !val1.isNumber) {
          parts.push(`${val1.name}(${val1.value})`);
        } else if (val1.type === AST_TYPES.IDENTIFIER) {
          parts.push(val1.name);
        } else if (val1.type === AST_TYPES.DIMENSION) {
          parts.push(`${val1.value}${val1.unit}`);
        } else if (val1.type === AST_TYPES.PERCENTAGE) {
          parts.push(`${val1.value}%`);
        } else {
          return;
        }
        switch (val2.type) {
          case AST_TYPES.CALC: {
            if (val2.isNumber) {
              return;
            }
            parts.push(`${val2.name}(${val2.value})`);
            break;
          }
          case AST_TYPES.DIMENSION: {
            parts.push(`${val2.value}${val2.unit}`);
            break;
          }
          case AST_TYPES.IDENTIFIER: {
            if (val2.name !== "auto") {
              parts.push(val2.name);
            }
            break;
          }
          case AST_TYPES.PERCENTAGE: {
            parts.push(`${val2.value}%`);
            break;
          }
          default: {
            return;
          }
        }
        parsedValues.push(parts.join(" "));
      }
    } else if (typeof value === "string") {
      parsedValues.push(value);
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
