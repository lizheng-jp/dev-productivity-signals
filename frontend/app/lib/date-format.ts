import { format } from "date-fns";

export const formatLocalDate = (date?: Date | null) => {
  if (!date) return undefined;
  return format(date, "yyyy-MM-dd");
};
