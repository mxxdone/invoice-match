import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "@/lib/utils"

const noticeVariants = cva("min-w-0 [&_p]:mt-1", {
  variants: {
    tone: {
      warning: "border-warning/30 bg-status-attention text-warning",
      success: "border-green/40 bg-mint text-green",
      neutral: "border-border bg-muted text-muted-foreground",
    },
    variant: {
      boxed: "rounded-sm border px-4 py-3",
      strip: "rounded-none border-0 border-l-2 px-5 py-4.25",
      inline: "border-0 bg-transparent p-0",
    },
    density: { default: "text-sm", compact: "text-label" },
  },
  defaultVariants: { tone: "warning", variant: "boxed", density: "default" },
})

type NoticeProps = React.HTMLAttributes<HTMLDivElement> &
  VariantProps<typeof noticeVariants>

const Notice = React.forwardRef<HTMLDivElement, NoticeProps>(
  ({ className, tone, variant, density, role = "status", ...props }, ref) => (
    <div
      ref={ref}
      role={role}
      data-slot="notice"
      className={cn(noticeVariants({ tone, variant, density }), className)}
      {...props}
    />
  )
)
Notice.displayName = "Notice"

export { Notice }
