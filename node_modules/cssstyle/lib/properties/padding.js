"use strict";

const parsers = require("../parsers");
const paddingTop = require("./paddingTop");
const paddingRight = require("./paddingRight");
const paddingBottom = require("./paddingBottom");
const paddingLeft = require("./paddingLeft");

const property = "padding";

module.exports.position = "edges";

module.exports.shorthandFor = new Map([
  [paddingTop.property, paddingTop],
  [paddingRight.property, paddingRight],
  [paddingBottom.property, paddingBottom],
  [paddingLeft.property, paddingLeft]
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
        min: 0,
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
    return parsedValues;
  }
};

module.exports.definition = {
  set(v) {
    v = parsers.prepareValue(v);
    if (parsers.hasVarFunc(v)) {
      for (const [longhand] of module.exports.shorthandFor) {
        this._setProperty(longhand, "");
      }
      this._setProperty(property, v);
    } else {
      const val = module.exports.parse(v, {
        globalObject: this._global
      });
      if (Array.isArray(val) || typeof val === "string") {
        const priority = this._priorities.get(property) ?? "";
        this._positionShorthandSetter(property, val, priority);
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
