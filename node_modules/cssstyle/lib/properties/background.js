"use strict";

const parsers = require("../parsers");
const backgroundImage = require("./backgroundImage");
const backgroundPosition = require("./backgroundPosition");
const backgroundSize = require("./backgroundSize");
const backgroundRepeat = require("./backgroundRepeat");
const backgroundOrigin = require("./backgroundOrigin");
const backgroundClip = require("./backgroundClip");
const backgroundAttachment = require("./backgroundAttachment");
const backgroundColor = require("./backgroundColor");

const property = "background";

module.exports.initialValues = new Map([
  [backgroundImage.property, "none"],
  [backgroundPosition.property, "0% 0%"],
  [backgroundSize.property, "auto"],
  [backgroundRepeat.property, "repeat"],
  [backgroundOrigin.property, "padding-box"],
  [backgroundClip.property, "border-box"],
  [backgroundAttachment.property, "scroll"],
  [backgroundColor.property, "transparent"]
]);

module.exports.shorthandFor = new Map([
  [backgroundImage.property, backgroundImage],
  [backgroundPosition.property, backgroundPosition],
  [backgroundSize.property, backgroundSize],
  [backgroundRepeat.property, backgroundRepeat],
  [backgroundOrigin.property, backgroundOrigin],
  [backgroundClip.property, backgroundClip],
  [backgroundAttachment.property, backgroundAttachment],
  [backgroundColor.property, backgroundColor]
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
  const values = parsers.splitValue(v, {
    delimiter: ","
  });
  const bgValues = [];
  const l = values.length;
  for (let i = 0; i < l; i++) {
    let bg = {
      [backgroundImage.property]: module.exports.initialValues.get(backgroundImage.property),
      [backgroundPosition.property]: module.exports.initialValues.get(backgroundPosition.property),
      [backgroundSize.property]: module.exports.initialValues.get(backgroundSize.property),
      [backgroundRepeat.property]: module.exports.initialValues.get(backgroundRepeat.property),
      [backgroundOrigin.property]: module.exports.initialValues.get(backgroundOrigin.property),
      [backgroundClip.property]: module.exports.initialValues.get(backgroundClip.property),
      [backgroundAttachment.property]: module.exports.initialValues.get(
        backgroundAttachment.property
      ),
      [backgroundColor.property]: module.exports.initialValues.get(backgroundColor.property)
    };
    if (l > 1 && i !== l - 1) {
      bg = {
        [backgroundImage.property]: module.exports.initialValues.get(backgroundImage.property),
        [backgroundPosition.property]: module.exports.initialValues.get(
          backgroundPosition.property
        ),
        [backgroundSize.property]: module.exports.initialValues.get(backgroundSize.property),
        [backgroundRepeat.property]: module.exports.initialValues.get(backgroundRepeat.property),
        [backgroundOrigin.property]: module.exports.initialValues.get(backgroundOrigin.property),
        [backgroundClip.property]: module.exports.initialValues.get(backgroundClip.property),
        [backgroundAttachment.property]: module.exports.initialValues.get(
          backgroundAttachment.property
        )
      };
    }
    const bgPosition = [];
    const bgSize = [];
    const bgRepeat = [];
    const bgBox = [];
    const bgParts = parsers.splitValue(values[i], {
      delimiter: "/"
    });
    if (!bgParts.length || bgParts.length > 2) {
      return;
    }
    const [bgPart1, bgPart2 = ""] = bgParts;
    const parts1 = parsers.splitValue(bgPart1);
    for (const part of parts1) {
      let partValid = false;
      for (const [longhand, value] of module.exports.shorthandFor) {
        if (parsers.isValidPropertyValue(longhand, part)) {
          partValid = true;
          switch (longhand) {
            case backgroundClip.property:
            case backgroundOrigin.property: {
              const parsedValue = value.parse(part, { globalObject });
              if (parsedValue) {
                bgBox.push(parsedValue);
              }
              break;
            }
            case backgroundColor.property: {
              if (i !== values.length - 1) {
                return;
              }
              const parsedValue = value.parse(part, { globalObject });
              if (parsedValue) {
                bg[longhand] = parsedValue;
              }
              break;
            }
            case backgroundPosition.property: {
              const parsedValue = value.parse(part, { globalObject });
              if (parsedValue) {
                bgPosition.push(parsedValue);
              }
              break;
            }
            case backgroundRepeat.property: {
              const parsedValue = value.parse(part, { globalObject });
              if (parsedValue) {
                bgRepeat.push(parsedValue);
              }
              break;
            }
            case backgroundSize.property: {
              break;
            }
            default: {
              const parsedValue = value.parse(part, { globalObject });
              if (parsedValue) {
                bg[longhand] = parsedValue;
              }
            }
          }
        }
      }
      if (!partValid) {
        return;
      }
    }
    if (bgPart2) {
      const parts2 = parsers.splitValue(bgPart2);
      for (const part of parts2) {
        let partValid = false;
        for (const [longhand, value] of module.exports.shorthandFor) {
          if (parsers.isValidPropertyValue(longhand, part)) {
            partValid = true;
            switch (longhand) {
              case backgroundClip.property:
              case backgroundOrigin.property: {
                const parsedValue = value.parse(part, { globalObject });
                if (parsedValue) {
                  bgBox.push(parsedValue);
                }
                break;
              }
              case backgroundColor.property: {
                if (i !== l - 1) {
                  return;
                }
                const parsedValue = value.parse(part, { globalObject });
                if (parsedValue) {
                  bg[longhand] = parsedValue;
                }
                break;
              }
              case backgroundPosition.property: {
                break;
              }
              case backgroundRepeat.property: {
                const parsedValue = value.parse(part, { globalObject });
                if (parsedValue) {
                  bgRepeat.push(parsedValue);
                }
                break;
              }
              case backgroundSize.property: {
                const parsedValue = value.parse(part, { globalObject });
                if (parsedValue) {
                  bgSize.push(parsedValue);
                }
                break;
              }
              default: {
                const parsedValue = value.parse(part, { globalObject });
                if (parsedValue) {
                  bg[longhand] = parsedValue;
                }
              }
            }
          }
        }
        if (!partValid) {
          return;
        }
      }
    }
    if (bgPosition.length) {
      const { parse: parser } = module.exports.shorthandFor.get(backgroundPosition.property);
      const value = parser(bgPosition.join(" "), { globalObject });
      if (value) {
        bg[backgroundPosition.property] = value;
      }
    }
    if (bgSize.length) {
      const { parse: parser } = module.exports.shorthandFor.get(backgroundSize.property);
      const value = parser(bgSize.join(" "), { globalObject });
      if (value) {
        bg[backgroundSize.property] = value;
      }
    }
    if (bgRepeat.length) {
      const { parse: parser } = module.exports.shorthandFor.get(backgroundRepeat.property);
      const value = parser(bgRepeat.join(" "), { globalObject });
      if (value) {
        bg[backgroundRepeat.property] = value;
      }
    }
    if (bgBox.length) {
      switch (bgBox.length) {
        case 1: {
          const [value] = bgBox;
          bg[backgroundOrigin.property] = value;
          bg[backgroundClip.property] = value;
          break;
        }
        case 2: {
          const [value1, value2] = bgBox;
          bg[backgroundOrigin.property] = value1;
          bg[backgroundClip.property] = value2;
          break;
        }
        default: {
          return;
        }
      }
    }
    bgValues.push(bg);
  }
  return bgValues;
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
      const bgValues = module.exports.parse(v, {
        globalObject: this._global
      });
      if (!Array.isArray(bgValues)) {
        return;
      }
      const bgMap = new Map([
        [backgroundImage.property, []],
        [backgroundPosition.property, []],
        [backgroundSize.property, []],
        [backgroundRepeat.property, []],
        [backgroundOrigin.property, []],
        [backgroundClip.property, []],
        [backgroundAttachment.property, []],
        [backgroundColor.property, []]
      ]);
      const backgrounds = [];
      for (const bgValue of bgValues) {
        const bg = [];
        for (const [longhand, value] of Object.entries(bgValue)) {
          if (value) {
            const arr = bgMap.get(longhand);
            arr.push(value);
            bgMap.set(longhand, arr);
            if (value !== module.exports.initialValues.get(longhand)) {
              if (longhand === backgroundSize.property) {
                bg.push(`/ ${value}`);
              } else {
                bg.push(value);
              }
            } else if (longhand === backgroundImage.property) {
              if (v === "none") {
                bg.push(value);
              }
            } else if (longhand === backgroundColor.property) {
              if (v === "transparent") {
                bg.push(value);
              }
            }
          }
        }
        backgrounds.push(bg.join(" "));
      }
      const priority = this._priorities.get(property) ?? "";
      for (const [longhand, value] of bgMap) {
        this._setProperty(longhand, value.join(", "), priority);
      }
      this._setProperty(property, backgrounds.join(", "), priority);
    }
  },
  get() {
    const v = this.getPropertyValue(property);
    if (parsers.hasVarFunc(v)) {
      return v;
    }
    const bgMap = new Map();
    let l = 0;
    for (const [longhand] of module.exports.shorthandFor) {
      const val = this.getPropertyValue(longhand);
      if (longhand === backgroundImage.property) {
        if (
          val === "none" &&
          v === "none" &&
          this.getPropertyValue(backgroundColor.property) === "transparent"
        ) {
          return val;
        }
        if (val !== module.exports.initialValues.get(longhand)) {
          const imgValues = parsers.splitValue(val, {
            delimiter: ","
          });
          l = imgValues.length;
          bgMap.set(longhand, imgValues);
        }
      } else if (longhand === backgroundColor.property) {
        if (val !== module.exports.initialValues.get(longhand) || v.includes(val)) {
          bgMap.set(longhand, [val]);
        }
      } else if (val !== module.exports.initialValues.get(longhand)) {
        bgMap.set(
          longhand,
          parsers.splitValue(val, {
            delimiter: ","
          })
        );
      }
    }
    if (l === 0) {
      const bgColArr = bgMap.get(backgroundColor.property);
      const background = bgColArr ? bgColArr[0] : null;
      if (background) {
        return background;
      }
      return "";
    }
    const bgValues = [];
    for (let i = 0; i < l; i++) {
      bgValues[i] = [];
    }
    for (const [longhand, values] of bgMap) {
      for (let i = 0; i < l; i++) {
        switch (longhand) {
          case backgroundColor.property: {
            if (i === l - 1) {
              const value = values[0];
              if (parsers.hasVarFunc(value)) {
                return "";
              }
              if (value && value !== module.exports.initialValues.get(longhand)) {
                const bgValue = bgValues[i];
                bgValue.push(value);
              }
            }
            break;
          }
          case backgroundSize.property: {
            const value = values[i];
            if (parsers.hasVarFunc(value)) {
              return "";
            }
            if (value && value !== module.exports.initialValues.get(longhand)) {
              const bgValue = bgValues[i];
              bgValue.push(`/ ${value}`);
            }
            break;
          }
          default: {
            const value = values[i];
            if (parsers.hasVarFunc(value)) {
              return "";
            }
            if (value && value !== module.exports.initialValues.get(longhand)) {
              const bgValue = bgValues[i];
              bgValue.push(value);
            }
          }
        }
      }
    }
    const backgrounds = [];
    for (const bgValue of bgValues) {
      backgrounds.push(bgValue.join(" "));
    }
    return backgrounds.join(", ");
  },
  enumerable: true,
  configurable: true
};

module.exports.property = property;
