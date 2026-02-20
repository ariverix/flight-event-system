import { FlightStage, MessageType } from './sequence';

export interface IncomingMessageRequest {
  messageType: MessageType;
  templateName: string;
  aircraftId: string;
  flightNumber?: string;
  metadataJson?: string;
}

export interface FlightStageChangeRequest {
  aircraftId: string;
  flightNumber?: string;
  newStage: FlightStage;
}

export interface MessageResponse {
  id: number;
  messageType: MessageType;
  templateName: string;
  aircraftId: string;
  flightNumber: string | null;
  receivedAt: string;
  metadataJson: string | null;
}
