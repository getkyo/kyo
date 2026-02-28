"use strict";

const parsers = require("../parsers");
const borderTopWidth = require("./borderTopWidth");
const borderRightWidth = require("./borderRightWidth");
const borderBottomWidth = require("./borderBottomWidth");
const borderLeftWidth = require("./borderLeftWidth");

const property = "border-width";
const shorthand = "border";

module.exports.shorthandFor = new Map([
  [borderTopWidth.property, borderTopWidth],
  [borderRightWidth.property, borderRightWidth],
  [borderBottomWidth.property, borderBottomWidth],
  [borderLeftWidth.property, borderLeftWidth]
]);

module.exports.parse = (v, opt = {}) => {
  const { globalObject } = opt;
  if (v === "") {
    return v;
  }
  const values = parsers.parsePropertyValue(property, v, {
    globalObject,
    inArray: true
  });
  const parsedValues = [];
  if (Array.isArray(values) && values.length) {
    if (values.length > 4) {
      return;
    }
    for (const value of values) {
      const parsedValue = parsers.resolveNumericValue([value], {
        length: values.length,
        type: "length"
      });
      if (!parsedValue) {
        return;
      }
      parsedValues.push(parsedValue);
    }
  } else if (typeof values === "string") {
    parsedValues.push(values);
  }
  if (parsedValues.length) {
    switch (parsedValues.length) {
      case 1: {
        return parsedValues;
      }
      case 2: {
        const [val1, val2] = parsedValues;
        if (val1 === val2) {
          return [val1];
        }
        return parsedValues;
      }
      case 3: {
        const [val1, val2, val3] = parsedValues;
        if (val1 === val3) {
          if (val1 === val2) {
            return [val1];
          }
          return [val1, val2];
        }
        return parsedValues;
      }
      case 4: {
        const [val1, val2, val3, val4] = parsedValues;
        if (val2 === val4) {
          if (val1 === val3) {
            if (val1 === val2) {
              return [val1];
            }
            return [val1, val2];
          }
          return [val1, val2, val3];
        }
        return parsedValues;
      }
      default:
    }
  }
};

module.exports.definition = {
  set(v) {
    v = parsers.prepareValue(v);
    if (parsers.hasVarFunc(v)) {
      this._borderSetter(property, v, "");
    } else {
      const val = module.exports.parse(v, {
        globalObject: this._global
      });
      if (Array.isArray(val) || typeof val === "string") {
        const priority =
          !this._priorities.get(shorthand) && this._priorities.has(property)
            ? this._priorities.get(property)
            : "";
        this._borderSetter(property, val, priority);
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
