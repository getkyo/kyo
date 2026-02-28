"use strict";

const parsers = require("../parsers");

const property = "background-repeat";
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
      let parsedValue = "";
      switch (value.length) {
        case 1: {
          const [part1] = value;
          const val1 = part1.type === AST_TYPES.IDENTIFIER && part1.name;
          if (val1) {
            parsedValue = val1;
          }
          break;
        }
        case 2: {
          const [part1, part2] = value;
          const val1 = part1.type === AST_TYPES.IDENTIFIER && part1.name;
          const val2 = part2.type === AST_TYPES.IDENTIFIER && part2.name;
          if (val1 && val2) {
            if (val1 === "repeat" && val2 === "no-repeat") {
              parsedValue = "repeat-x";
            } else if (val1 === "no-repeat" && val2 === "repeat") {
              parsedValue = "repeat-y";
            } else if (val1 === val2) {
              parsedValue = val1;
            } else {
              parsedValue = `${val1} ${val2}`;
            }
          }
          break;
        }
        default:
      }
      if (parsedValue) {
        parsedValues.push(parsedValue);
      } else {
        return;
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
