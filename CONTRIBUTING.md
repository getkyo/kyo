# How to Make Contributions

## 📋 Requirements

Before you begin, make sure you have the following installed:

✅ **Java 21** (JDK)  
✅ **Scala**  
✅ **sbt** (Scala Build Tool)  
✅ **Git**

---

## 🛠 Local Setup & Building Guide

This guide walks you through setting up the project on **Linux, macOS, and Windows**.

---

### 🚀 Building the Project Locally

Once the requirements are installed, follow these steps:

#### **1 Clone the Repository**
```bash
git clone https://github.com/c0d33ngr/kyo.git
cd kyo
```

#### **2 Run Build Commands**
The project has different builds for **JVM, JavaScript, and Native** versions.

#### ✅ **JVM Build**
```bash
sbt '+kyoJVM/test'
```

#### ✅ **JavaScript Build**
```bash
sbt '+kyoJS/test'
```

#### ✅ **Native Build**
```bash
sbt '+kyoNative/Test/compile'
```
