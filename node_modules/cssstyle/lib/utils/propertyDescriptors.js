"use strict";

const parsers = require("../parsers");

const { AST_TYPES } = parsers;

const getPropertyDescriptor = (property) => ({
  set(v) {
    const value = parsers.prepareValue(v);
    if (parsers.hasVarFunc(value)) {
      this._setProperty(property, value);
    } else {
      const parsedValue = parsers.parsePropertyValue(property, v, {
        globalObject: this._global,
        inArray: true
      });
      if (Array.isArray(parsedValue)) {
        if (parsedValue.length === 1) {
          const [{ name, type, value: itemValue }] = parsedValue;
          switch (type) {
            case AST_TYPES.CALC: {
              this._setProperty(property, `${name}(${itemValue})`);
              break;
            }
            case AST_TYPES.GLOBAL_KEYWORD:
            case AST_TYPES.IDENTIFIER: {
              // Set the normalized name for keywords or identifiers.
              this._setProperty(property, name);
              break;
            }
            default: {
              // Set the prepared value for Dimension, Function, etc.
              this._setProperty(property, value);
            }
          }
        } else {
          // Set the prepared value for lists containing multiple values.
          this._setProperty(property, value);
        }
      } else if (typeof parsedValue === "string") {
        // Empty string.
        this._setProperty(property, parsedValue);
      }
    }
  },
  get() {
    return this.getPropertyValue(property);
  },
  enumerable: true,
  configurable: true
});

module.exports = {
  getPropertyDescriptor
};
