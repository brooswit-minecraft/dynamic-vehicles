FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jdk xvfb xauth x11-utils imagemagick xdotool \
    libgl1 libglx-mesa0 libgl1-mesa-dri libegl1 libopenal1 libxcursor1 libxrandr2 libxxf86vm1 libxi6 libxrender1 libxext6 libxtst6 \
    fontconfig python3 curl ca-certificates git unzip \
 && rm -rf /var/lib/apt/lists/*
ENV LIBGL_ALWAYS_SOFTWARE=1 MESA_GL_VERSION_OVERRIDE=4.5 MESA_GLSL_VERSION_OVERRIDE=450
