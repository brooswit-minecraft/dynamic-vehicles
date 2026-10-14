# MINECRAFT-355

- Fixed client-side skid decals being suppressed because the server-authoritative vehicle's client copy
  does not maintain a reliable `onGround` flag. Qualifying slip now checks per-wheel block support and uses
  each vehicle's actual wheel mounts, while retaining the existing spacing, lifetime, fade, and render caps.
