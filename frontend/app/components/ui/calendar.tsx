"use client"

import * as React from "react"
import { DayFlag, DayPicker, SelectionState, UI } from "react-day-picker"
import { ChevronLeft, ChevronRight, ChevronUp, ChevronDown } from "lucide-react"

import { cn } from "./Common"
import { buttonVariants } from "./button"

export type CalendarProps = React.ComponentProps<typeof DayPicker>

function Chevron({ orientation = "left" }: { orientation?: "left" | "right" | "up" | "down" }) {
  switch (orientation) {
    case "left":
      return <ChevronLeft className="h-4 w-4" />
    case "right":
      return <ChevronRight className="h-4 w-4" />
    case "up":
      return <ChevronUp className="h-4 w-4" />
    case "down":
      return <ChevronDown className="h-4 w-4" />
    default:
      return null
  }
}

function Calendar({ className, classNames, showOutsideDays = true, ...props }: CalendarProps) {
  return (
    <DayPicker
      showOutsideDays={showOutsideDays}

      // 変更前
      //className={cn("p-3", className)}

      // 変更後（bg-white を追加）
      className={cn("p-3 bg-white", className)}

      classNames={{
        [UI.Months]: "relative",
        [UI.Month]: "space-y-4",
        [UI.MonthCaption]: "flex justify-center items-center h-7",
        [UI.CaptionLabel]: "text-sm font-semibold text-slate-800",
        [UI.PreviousMonthButton]: cn(
          buttonVariants({ variant: "outline" }),
          "h-7 w-7 border-slate-200 bg-white p-0 text-slate-500 opacity-70 hover:bg-slate-50 hover:opacity-100"
        ),
        [UI.NextMonthButton]: cn(
          buttonVariants({ variant: "outline" }),
          "h-7 w-7 border-slate-200 bg-white p-0 text-slate-500 opacity-70 hover:bg-slate-50 hover:opacity-100"
        ),
        [UI.MonthGrid]: "w-full border-collapse space-y-1",
        [UI.Weekdays]: "flex",
        [UI.Weekday]: "w-9 rounded-md text-[0.8rem] font-semibold text-slate-400",
        [UI.Week]: "flex w-full mt-2",
        [UI.Day]:
          "relative h-9 w-9 p-0 text-center text-sm focus-within:relative focus-within:z-20 [&:has([aria-selected])]:bg-blue-50 first:[&:has([aria-selected])]:rounded-l-md last:[&:has([aria-selected])]:rounded-r-md",
        [UI.DayButton]: cn(
          buttonVariants({ variant: "ghost" }),
          "h-9 w-9 rounded-md p-0 font-normal text-slate-700 hover:bg-slate-100 hover:text-slate-900 aria-selected:opacity-100"
        ),
        [SelectionState.selected]:
          "[&>button]:border [&>button]:border-blue-200 [&>button]:bg-blue-100 [&>button]:font-semibold [&>button]:text-blue-800 [&>button]:shadow-sm [&>button]:hover:bg-blue-100 [&>button]:hover:text-blue-900 [&>button]:focus:bg-blue-100 [&>button]:focus:text-blue-900",
        [SelectionState.range_middle]: "[&>button]:bg-blue-50 [&>button]:text-blue-700 [&>button]:hover:bg-blue-100",
        [DayFlag.today]: "[&>button]:border [&>button]:border-blue-300 [&>button]:font-semibold [&>button]:text-blue-700",
        [DayFlag.outside]: "[&>button]:text-slate-300",
        [DayFlag.disabled]: "[&>button]:text-slate-300",
        [DayFlag.hidden]: "invisible",
        ...classNames,
      }}
      components={{
        Chevron: (p) => <Chevron {...p} />,
      }}
      {...props}
    />
  )
}

Calendar.displayName = "Calendar"
export { Calendar }
