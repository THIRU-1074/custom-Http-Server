### ⚡⚡Jolt⚡⚡ - A custom Http Frame work 🚀

A lightweight, customizable HTTP server written in Java.  
It handles HTTP/1.0 and HTTP/1.1 requests, allows defining endpoints, and supports flexible response manipulation.

- 🚂inspired from simplicity of Express.js API, the Framework Interface would appear in similar way 

- 🧶multithreaded FrameWork, 
    - Can handle multiple Clients concurrently 
    - Can receive multiple Requests when the Request handling and Responding is on Progress.
    - The responding order is maintained with Response queueing ensuring consistency accross important db Transactions.

- 💡The framework has url Routers to isolate the API endpoints, it also allows divide and conquer in design and teams to work on developing different Routers and combine them for a specific domain.

-  A simple Attendance Application has been made with Jolt (ie the framework) to demonstrate the working.

---

## 📦 Features

- Supports **GET**, **POST**, **PUT**, **DELETE**, and other HTTP methods.
- Easy endpoint registration with full control over requests and responses.
- Inspect incoming headers, body, and path data.
- Set custom headers, status codes, and body in responses.
- Start server on any port and shut it down gracefully.

---

## 🛠️ Getting Started

### ✅ Prerequisites

- Java 8+
- Maven (for building)

### 📥 Clone and Build

```bash
git clone https://github.com/THIRU-1074/custom-Http-Server.git
cd custom-Http-Server
mvn clean package
```
### Try your Stuff

- Remove the Attendance.java and NotificationServer.java ( A webhook Service App), then create your own app.
- The app could be split into multiple files with logically partitioned APIs
- Make sure to call all the (your_apps).java in our Main.java.

### To play around with Framework 

- Make sure to get a good grip on thr protocol
- Refer any docs of your interest
- But the best and highly recommended by me is: 
     (https://developer.mozilla.org/en-US/docs/Web/HTTP)

### Key Tools used

- Postman API Testing (Provides everything from different MIME types to different Auth Type).
- JWT tester was helpful with developing JWT functionalities.
    (https://jwt.io/)
