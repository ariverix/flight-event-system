import React from 'react';
import { BaseEdge, EdgeLabelRenderer, EdgeProps, getSmoothStepPath } from '@xyflow/react';

// Кастомное ребро поверх smoothstep: подпись рендерится через
// EdgeLabelRenderer строго в середине пути (getSmoothStepPath возвращает
// её координаты), плюс опциональный вертикальный сдвиг (data.labelOffsetY)
// — чтобы разводить подписи рёбер, которые иначе оказались бы друг на друге.
export const LabeledEdge: React.FC<EdgeProps> = (props) => {
  const {
    sourceX, sourceY, targetX, targetY,
    sourcePosition, targetPosition,
    style, label, labelStyle, labelBgStyle, labelBgPadding,
    markerEnd, markerStart, pathOptions, data,
  } = props;

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX, sourceY, targetX, targetY,
    sourcePosition, targetPosition,
    borderRadius: pathOptions?.borderRadius,
    offset: pathOptions?.offset,
  });

  const offsetX = (data?.labelOffsetX as number | undefined) ?? 0;
  const offsetY = (data?.labelOffsetY as number | undefined) ?? 0;
  const padX = labelBgPadding ? labelBgPadding[0] : 5;
  const padY = labelBgPadding ? labelBgPadding[1] : 3;

  return (
    <>
      <BaseEdge path={edgePath} markerEnd={markerEnd} markerStart={markerStart} style={style} />
      {label != null && label !== '' && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX + offsetX}px,${labelY + offsetY}px)`,
              pointerEvents: 'none',
              whiteSpace: 'nowrap',
              fontSize: labelStyle?.fontSize,
              fontWeight: labelStyle?.fontWeight,
              color: labelStyle?.fill as string | undefined,
              background: labelBgStyle?.fill as string | undefined,
              border: labelBgStyle?.stroke
                ? `${labelBgStyle.strokeWidth ?? 1}px solid ${labelBgStyle.stroke}`
                : undefined,
              borderRadius: labelBgStyle?.rx ?? 5,
              padding: `${padY}px ${padX}px`,
              zIndex: (props as { zIndex?: number }).zIndex,
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
};
