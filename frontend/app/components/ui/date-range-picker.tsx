"use client"

import * as React from "react"
import { addYears, format } from "date-fns"
import { ja, enUS } from "date-fns/locale"
import { Calendar as CalendarIcon } from "lucide-react"
import { type DateRange, type OnSelectHandler } from "react-day-picker"
import { useLocale, useTranslations } from "next-intl"

import { cn } from "./Common"
import { Button } from "./button"
import { Calendar } from "./calendar"
import { Popover, PopoverContent, PopoverTrigger } from "./popover"

interface DatePickerWithRangeProps extends React.HTMLAttributes<HTMLDivElement> {
  date: DateRange | undefined
  setDate: (date: DateRange | undefined) => void
  triggerClassName?: string
}

export function DatePickerWithRange({ className, date, setDate, triggerClassName }: DatePickerWithRangeProps) {
  const t = useTranslations("DatePicker")
  const isJapanese = useLocale() === 'ja'
  const [open, setOpen] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)

  // 表示切替のため state で管理（これがあることで文言が再描画される）
  const [step, setStep] = React.useState<"from" | "to">("from")

  // 2回目クリック時に参照する開始日
  const fromRef = React.useRef<Date | undefined>(undefined)

  // Popoverを開いたら、必ず「開始日設定」からスタート
  React.useEffect(() => {
    if (open) {
      Promise.resolve().then(() => {
        setStep("from")
        fromRef.current = undefined
        setError(null)
      })
    }
  }, [open])

  const isOutsideMaxRange = (from: Date, to: Date) => {
    return to < addYears(from, -1) || to > addYears(from, 1)
  }

  const handleDayClick = (day: Date) => {
    // 1回目クリック：from のみ設定、閉じない、表示を「終了日」へ
    if (step === "from") {
      fromRef.current = day
      setDate({ from: day, to: undefined })
      setStep("to")
      setError(null)
      return
    }

    // 2回目クリック：to を設定して閉じる（逆順クリックは入れ替え）
    const from = fromRef.current ?? day
    if (isOutsideMaxRange(from, day)) {
      setError(t("maxRangeError"))
      return
    }

    const range: DateRange = day < from ? { from: day, to: from } : { from, to: day }

    setDate(range)
    setOpen(false)

    // 次回のためにリセット（open でもリセットされるが念のため）
    setStep("from")
    fromRef.current = undefined
    setError(null)
  }

  const headerText = step === "from" ? t("selectStart") : t("selectEnd")
  const handleSelect: OnSelectHandler<DateRange | undefined> = (_selectedRange, selectedDay) => {
    handleDayClick(selectedDay)
  }

  return (
    <div className={cn("grid gap-2", className)}>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id="date"
            variant="outline"
            className={cn(
              "h-10 min-w-0 w-full justify-start rounded-lg border-slate-200 bg-slate-100 px-3 text-left text-sm font-semibold text-slate-800 shadow-sm hover:border-slate-300 hover:bg-slate-200 focus-visible:border-blue-500 focus-visible:ring-4 focus-visible:ring-blue-500/10 focus-visible:ring-offset-0",
              triggerClassName,
              !date?.from && "text-slate-400"
            )}
          >
            <CalendarIcon className="mr-2 h-4 w-4 shrink-0 text-blue-500" />
            <span className="truncate">
              {date?.from ? (
                date.to ? (
                  <>
                    {format(date.from, "yyyy/MM/dd")} - {format(date.to, "yyyy/MM/dd")}
                  </>
                ) : (
                  format(date.from, "yyyy/MM/dd")
                )
              ) : (
                t("pickDate")
              )}
            </span>
          </Button>
        </PopoverTrigger>

        <PopoverContent className="w-auto overflow-hidden rounded-lg border border-slate-200 bg-white p-0 shadow-lg" align="start">
          {/* カレンダー上部の案内文 */}
          <div className="border-b border-slate-100 bg-slate-50/80 px-3 py-2 text-center">
            <div className="mb-2 grid grid-cols-2 gap-2">
              {(['from', 'to'] as const).map(target => (
                <button key={target} type="button" disabled={target === 'to' && !date?.from}
                  aria-pressed={step === target}
                  onClick={() => { setStep(target); fromRef.current = date?.from; setError(null) }}
                  className={cn('rounded border px-2 py-2 text-xs disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-blue-600', step === target ? 'border-blue-500 bg-blue-50 text-blue-700' : 'border-slate-200 bg-white text-slate-600')}>
                  <span className="block">{isJapanese ? (target === 'from' ? '開始日' : '終了日') : (target === 'from' ? 'Start date' : 'End date')}</span>
                  <span>{date?.[target] ? format(date[target]!, 'yyyy/MM/dd') : '—'}</span>
                </button>
              ))}
            </div>
            <div className="text-sm font-semibold text-slate-700">{headerText}</div>
            {error && <div className="mt-1 text-xs font-medium text-red-600">{error}</div>}
          </div>

          <Calendar
            locale={isJapanese ? ja : enUS}
            formatters={{ formatCaption: month => format(month, isJapanese ? 'yyyy年M月' : 'yyyy/MM') }}
            mode="range"
            selected={date}
            /**
             * 重要：
             * onDayClick を使うと、DayPicker(range) の内部選択計算と競合し、
             * 「旧レンジ起点で伸びた選択表示」が一瞬/継続で出ることがあります。
             *
             * onSelect の第2引数（クリックされた日付）だけを用いて、
             * こちらの 1回目/2回目ロジックで selected(date) を更新します。
             */
            onSelect={handleSelect}
            defaultMonth={date?.from}
            numberOfMonths={1}
            className="bg-white"
          />
        </PopoverContent>
      </Popover>
    </div>
  )
}
