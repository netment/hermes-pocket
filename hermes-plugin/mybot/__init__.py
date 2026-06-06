import importlib
import os
import sys

# Dynamically import the adapter module from the same directory
_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _dir)
from adapter import register

__all__ = ["register"]
