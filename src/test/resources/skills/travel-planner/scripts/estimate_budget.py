#!/usr/bin/env python3
"""
Estimate travel budget.
Usage: python estimate_budget.py "<flight_price> <hotel_price_per_night> <nights>"
"""
import sys

def estimate(args):
    try:
        parts = args.strip().split()
        flight = float(parts[0])
        hotel_per_night = float(parts[1])
        nights = int(parts[2]) if len(parts) > 2 else 3
        total = flight + hotel_per_night * nights
        return f"预算估算：机票 ¥{flight:.0f} + 酒店({nights}晚) ¥{hotel_per_night * nights:.0f} = 合计 ¥{total:.0f}"
    except Exception as e:
        return f"预算计算失败：{e}，请提供格式：<机票价格> <每晚酒店价格> [晚数]"

if __name__ == "__main__":
    args = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else ""
    print(estimate(args))
