import { LineChart as ELineChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import * as echarts from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { useEffect, useRef } from "react";

// Tree-shaken ECharts: only the line chart + grid/tooltip + canvas renderer are bundled,
// instead of the full ~1 MB `echarts` package (§4 charts = Apache ECharts).
echarts.use([ELineChart, GridComponent, TooltipComponent, CanvasRenderer]);

/** Thin ECharts time-series wrapper. Pass `className` (e.g. "h-full w-full") to fill a flex panel. */
export function LineChart({
  name,
  points,
  className = "h-80 w-full",
}: {
  name: string;
  points: [number, number][];
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current, undefined, { renderer: "canvas" });
    chart.setOption({
      backgroundColor: "transparent",
      tooltip: { trigger: "axis" },
      grid: { left: 56, right: 20, top: 24, bottom: 36 },
      xAxis: { type: "time" },
      yAxis: { type: "value", scale: true },
      series: [
        { name, type: "line", showSymbol: false, smooth: true, data: points },
      ],
    });
    // ResizeObserver tracks the container (flex/grid) not just the window, so the chart fills its panel.
    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(ref.current);
    return () => {
      ro.disconnect();
      chart.dispose();
    };
  }, [name, points]);

  return <div ref={ref} className={className} />;
}
