__version__ = "1.1.0"
# The oldest app this server works with; the app warns when it is older (shown by /health).
MIN_APP_VERSION = "1.1.0"

try:
    # Written by CI into the installer builds (server-agent.yml): the build number of that run.
    from ._build import BUILD
except ImportError:  # a source checkout or Docker image
    BUILD = ""

# What the server says it is: 1.1.0.81 for build 81 of 1.1.0 — the same as the .deb and the Windows installer.
FULL_VERSION = f"{__version__}.{BUILD}" if BUILD else __version__
