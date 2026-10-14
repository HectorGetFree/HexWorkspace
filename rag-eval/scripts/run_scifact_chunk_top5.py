#!/usr/bin/env python3
"""SciFact 300 Query：生产 Hybrid Top5 Chunk，不限制同文档 Chunk 数量。"""
from run_scifact_production_hybrid import main

if __name__ == "__main__":
    main(unlimited=True)
