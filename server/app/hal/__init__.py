"""Hardware abstraction layer.

Everything that talks to a switch goes through a SwitchDriver. Two
implementations exist: the in-memory emulator (default) and, later, real
SSH/SNMP drivers. Real drivers are always wrapped in WriteGuard, so the
state-changing methods refuse to run unless ALLOW_DEVICE_WRITES is set.
"""
